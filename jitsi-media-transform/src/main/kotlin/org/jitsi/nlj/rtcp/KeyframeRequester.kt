/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.nlj.rtcp

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.utils.MediaType
import org.jitsi.utils.RateLimit
import org.jitsi.utils.durationOfDoubleSeconds
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.min
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KeyframeRequester] handles a few things around keyframes:
 * 1) The bridge requesting a keyframe (e.g. in order to switch) via the [KeyframeRequester#requestKeyframe]
 * method which will create a new keyframe request and forward it
 * 2) PLI/FIR translation.  If a PLI or FIR packet is forwarded through here, this class may translate it depending
 * on what the client supports
 * 3) Aggregation.  This class will pace outgoing requests such that we don't spam the sender
 */
class KeyframeRequester @JvmOverloads constructor(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemDefaultZone()
) : TransformerNode("Keyframe Requester") {
    private val logger = createChildLogger(parentLogger)

    // Map the tuple of requester and SSRC to a rate limiter
    private val perReceiverKeyframeLimiter = mutableMapOf<String, MutableMap<Long, RateLimit>>()
    private val perSourceKeyframeLimiter = mutableMapOf<Long, RateLimit>()
    private val keyframeLimiterSyncRoot = Any()
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private var localSsrc: Long? = null
    private var waitInterval = minInterval

    // Stats

    // Number of PLI/FIRs received and forwarded to the endpoint.
    private var numPlisForwarded: Int = 0
    private var numFirsForwarded: Int = 0

    // Number of PLI/FIRs received but dropped due to throttling.
    private var numPlisDropped: Int = 0
    private var numFirsDropped: Int = 0

    // Number of PLI/FIRs generated as a result of an API request or due to translation between PLI/FIR.
    private var numPlisGenerated: Int = 0
    private var numFirsGenerated: Int = 0

    // Number of calls to requestKeyframe
    private var numApiRequests: Int = 0

    // Number of calls to requestKeyframe ignored due to throttling
    private var numApiRequestsDropped: Int = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val pliOrFirPacket = packetInfo.getPliOrFirPacket() ?: return packetInfo

        val now = clock.instant()
        val sourceSsrc: Long
        val canSend: Boolean
        val forward: Boolean
        when (pliOrFirPacket) {
            is RtcpFbPliPacket -> {
                sourceSsrc = pliOrFirPacket.mediaSourceSsrc
                canSend = canSendKeyframeRequest(packetInfo.endpointId, sourceSsrc, now)
                forward = canSend && streamInformationStore.supportsPli
                if (forward) numPlisForwarded++
                if (!canSend) numPlisDropped++
            }
            is RtcpFbFirPacket -> {
                sourceSsrc = pliOrFirPacket.mediaSenderSsrc
                canSend = canSendKeyframeRequest(packetInfo.endpointId, sourceSsrc, now)
                // When both are supported, we favor generating a PLI rather than forwarding a FIR
                forward = canSend && streamInformationStore.supportsFir && !streamInformationStore.supportsPli
                if (forward) {
                    // When we forward a FIR we need to update the seq num.
                    pliOrFirPacket.seqNum = firCommandSequenceNumber.incrementAndGet()
                    // We manage the seq num space, so we should use the same SSRC
                    localSsrc?.let { pliOrFirPacket.mediaSenderSsrc = it }
                    numFirsForwarded++
                }
                if (!canSend) numFirsDropped++
            }
            // This is not possible, but the compiler doesn't know it.
            else -> throw IllegalStateException("Packet is neither PLI nor FIR")
        }

        if (!forward && canSend) {
            doRequestKeyframe(sourceSsrc)
        }

        return if (forward) packetInfo else null
    }

    /**
     * Returns 'true' when at least one method is supported, AND this requester hasn't sent a request very recently.
     */
    private fun canSendKeyframeRequest(requesterID: String?, mediaSsrc: Long, now: Instant): Boolean {
        if (!streamInformationStore.supportsPli && !streamInformationStore.supportsFir) {
            return false
        }
        if (requesterID == null) {
            /* This request is either triggered by a dominant speaker switch, or possibly came over a proxy connection.
             *  Always allow it. */
            return true
        }
        synchronized(keyframeLimiterSyncRoot) {
            val perReceiverLimiter = perReceiverKeyframeLimiter.computeIfAbsent(requesterID) { mutableMapOf() }
                .computeIfAbsent(mediaSsrc) {
                    RateLimit(
                        defaultMinInterval = minInterval,
                        maxRequests = maxRequests,
                        interval = maxRequestInterval
                    )
                }
            if (!perReceiverLimiter.accept(now, waitInterval)) {
                logger.cdebug {
                    "Ignoring keyframe request for $mediaSsrc from $requesterID, per-receiver rate limited"
                }
                return false
            }

            val perSourceLimiter = perSourceKeyframeLimiter.computeIfAbsent(mediaSsrc) {
                RateLimit(
                    defaultMinInterval = sourceWideMinInterval,
                    maxRequests = sourceWideMaxRequests,
                    interval = sourceWideMaxRequestInterval
                )
            }

            if (!perSourceLimiter.accept(now, waitInterval)) {
                logger.cdebug { "Ignoring keyframe request for $mediaSsrc from $requesterID, per-source rate limited" }
                return false
            }

            logger.cdebug { "Keyframe requester requesting keyframe for $mediaSsrc, requested by $requesterID" }
            return true
        }
    }

    fun requestKeyframe(requesterID: String?, mediaSsrc: Long? = null) {
        val ssrc = mediaSsrc ?: streamInformationStore.primaryMediaSsrcs.firstOrNull() ?: run {
            numApiRequestsDropped++
            logger.cdebug { "No video SSRC found to request keyframe" }
            return
        }
        numApiRequests++
        if (!canSendKeyframeRequest(requesterID, ssrc, clock.instant())) {
            numApiRequestsDropped++
            return
        }

        doRequestKeyframe(ssrc)
    }

    private fun doRequestKeyframe(mediaSsrc: Long) {
        val pkt = when {
            streamInformationStore.supportsPli -> {
                numPlisGenerated++
                RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build()
            }
            streamInformationStore.supportsFir -> {
                numFirsGenerated++
                RtcpFbFirPacketBuilder(
                    mediaSenderSsrc = mediaSsrc,
                    firCommandSeqNum = firCommandSequenceNumber.incrementAndGet()
                ).build()
            }
            else -> {
                logger.warn("Can not send neither PLI nor FIR")
                return
            }
        }

        next(PacketInfo(pkt))
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetLocalSsrcEvent -> {
                if (event.mediaType == MediaType.VIDEO) {
                    localSsrc = event.ssrc
                }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("wait_interval_ms", waitInterval.toMillis())
            addNumber("num_api_requests", numApiRequests)
            addNumber("num_api_requests_dropped", numApiRequestsDropped)
            addNumber("num_firs_dropped", numFirsDropped)
            addNumber("num_firs_generated", numFirsGenerated)
            addNumber("num_firs_forwarded", numFirsForwarded)
            addNumber("num_plis_dropped", numPlisDropped)
            addNumber("num_plis_generated", numPlisGenerated)
            addNumber("num_plis_forwarded", numPlisForwarded)
        }
    }

    override fun statsJson() = super.statsJson().apply {
        this["num_api_requests"] = numApiRequests
        this["num_api_requests_dropped"] = numApiRequestsDropped
        this["num_firs_dropped"] = numFirsDropped
        this["num_firs_generated"] = numFirsGenerated
        this["num_firs_forwarded"] = numFirsForwarded
        this["num_plis_dropped"] = numPlisDropped
        this["num_plis_generated"] = numPlisGenerated
        this["num_plis_forwarded"] = numPlisForwarded
    }

    fun onRttUpdate(newRtt: Double) {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitInterval = min(minInterval, durationOfDoubleSeconds((newRtt + 10) / 1e3))
    }

    companion object {
        private val minInterval: Duration by config {
            "jmt.keyframe.min-interval".from(JitsiConfig.newConfig)
        }
        private val maxRequests: Int by config {
            "jmt.keyframe.max-requests".from(JitsiConfig.newConfig)
        }
        private val maxRequestInterval: Duration by config {
            "jmt.keyframe.max-request-interval".from(JitsiConfig.newConfig)
        }

        private val sourceWideMinInterval: Duration by config {
            "jmt.keyframe.source-wide-min-interval".from(JitsiConfig.newConfig)
        }
        private val sourceWideMaxRequests: Int by config {
            "jmt.keyframe.source-wide-max-requests".from(JitsiConfig.newConfig)
        }
        private val sourceWideMaxRequestInterval: Duration by config {
            "jmt.keyframe.source-wide-max-request-interval".from(JitsiConfig.newConfig)
        }
    }
}

private fun PacketInfo.getPliOrFirPacket(): RtcpFbPacket? {
    return when (val pkt = packet) {
        // We intentionally ignore compound RTCP packets in order to avoid unnecessary parsing. We can do this because:
        // 1. Compound packets coming from remote endpoint are terminated in RtcpTermination
        // 2. Whenever a PLI or FIR is generated in our code, it is not part of a compound packet.
        is RtcpFbFirPacket -> pkt
        is RtcpFbPliPacket -> pkt
        else -> null
    }
}
