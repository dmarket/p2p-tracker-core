package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.port.host.ElapsedClock

/** JVM: the monotonic fallback; no host here passes a lease clock to the loop. */
actual fun platformElapsedClock(): ElapsedClock = MonotonicElapsedClock()
