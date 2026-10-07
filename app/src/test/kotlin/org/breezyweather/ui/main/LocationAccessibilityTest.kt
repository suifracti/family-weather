package org.breezyweather.ui.main

import breezyweather.domain.location.model.Location
import breezyweather.domain.location.model.OrchardLocationPolicy
import io.kotest.matchers.shouldBe
import org.breezyweather.ui.main.adapters.location.LocationAccessibility
import org.junit.jupiter.api.Test

class LocationAccessibilityTest {
    @Test fun `protected orchard never announces delete swipe`() {
        LocationAccessibility.deletionHint(OrchardLocationPolicy.createWorksiteLocation(), "You can swipe left to delete this item") shouldBe "工作地 / 果园，不可删除"
    }

    @Test fun `other cities including same name keep existing delete hint`() {
        val other = Location(latitude = 25.28, longitude = 110.29, customName = "工作地 / 果园", forecastSource = "china")
        LocationAccessibility.deletionHint(other, "existing delete hint") shouldBe "existing delete hint"
    }
}
