package app.ft.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LauncherComponentsTest {
    private data class Entry(val pkg: String, val activity: String, val label: String)

    @Test fun twoActivitiesInTheSamePackageAreNotCollapsed() {
        val list = listOf(
            Entry("com.example", "com.example.Main", "Main"),
            Entry("com.example", "com.example.Settings", "Settings"),
            Entry("com.example", "com.example.Main", "Duplicate resolver row"),
            Entry("com.other", "com.other.Main", "Other")
        )
        val result = LauncherComponents.uniqueByComponent(list, { it.pkg }, { it.activity })
        assertEquals(3, result.size)
        assertEquals(listOf("Main", "Settings", "Other"), result.map { it.label })
    }

    @Test fun drawerKeyIsStableAndActivityScoped() {
        val a = LauncherComponents.key("com.example", "com.example.Main")
        val b = LauncherComponents.key("com.example", "com.example.Settings")
        assertNotEquals(a, b)
        assertEquals(a, LauncherComponents.key("com.example", "com.example.Main"))
    }
}
