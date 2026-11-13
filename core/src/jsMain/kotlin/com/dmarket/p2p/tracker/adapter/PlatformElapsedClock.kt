package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.ElapsedClock

/** JS: the monotonic fallback; the extension and the headless host pass no lease clock to the loop. */
actual fun platformElapsedClock(): ElapsedClock = MonotonicElapsedClock()
