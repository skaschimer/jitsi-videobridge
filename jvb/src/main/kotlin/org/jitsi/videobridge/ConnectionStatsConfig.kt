/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.videobridge

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import java.time.Duration

class ConnectionStatsConfig private constructor() {
    companion object {
        val enabled: Boolean by config(
            "videobridge.connection-stats.enabled".from(JitsiConfig.newConfig)
        )
        val interval: Duration by config(
            "videobridge.connection-stats.interval".from(JitsiConfig.newConfig)
        )
    }
}
