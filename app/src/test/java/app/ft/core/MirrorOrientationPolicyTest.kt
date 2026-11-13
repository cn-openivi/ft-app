package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorOrientationPolicyTest {
    @Test fun launcherAlwaysUsesPortrait() {
        assertEquals(0, MirrorOrientationPolicy.desired("com.maps", "com.launcher", "com.launcher"))
        assertEquals(0, MirrorOrientationPolicy.desired("com.launcher", "com.launcher", "com.launcher"))
    }
    @Test fun targetAppLandscapeAndOtherWindowsUntouched() {
        assertEquals(1, MirrorOrientationPolicy.desired("com.maps", "com.launcher", "com.maps"))
        assertEquals(null, MirrorOrientationPolicy.desired("com.maps", "com.launcher", "com.keyboard"))
        assertEquals(null, MirrorOrientationPolicy.desired("com.maps", "com.launcher", "com.android.systemui"))
    }
}
