/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.shade

import android.animation.Animator
import android.app.StatusBarManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Trace
import android.os.Trace.TRACE_TAG_APP
import android.provider.AlarmClock
import android.util.Pair
import android.view.DisplayCutout
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.widget.TextView
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.qs.ChipVisibilityListener
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.shade.ShadeHeaderController.Companion.LARGE_SCREEN_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeHeaderController.Companion.QQS_HEADER_CONSTRAINT
import com.android.systemui.shade.ShadeHeaderController.Companion.QS_HEADER_CONSTRAINT
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.VariableDateView
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.ViewController
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named

/**
 * Controller for QS header.
 *
 * [header] is a [MotionLayout] that has two transitions:
 * * [HEADER_TRANSITION_ID]: [QQS_HEADER_CONSTRAINT] <-> [QS_HEADER_CONSTRAINT] for portrait
 *   handheld device configuration.
 * * [LARGE_SCREEN_HEADER_TRANSITION_ID]: [LARGE_SCREEN_HEADER_CONSTRAINT] for all other
 *   configurations
 */
@CentralSurfacesScope
class ShadeHeaderController
@Inject
constructor(
    @Named(SHADE_HEADER) private val header: MotionLayout,
    private val statusBarIconController: StatusBarIconController,
    private val tintedIconManagerFactory: StatusBarIconController.TintedIconManager.Factory,
    private val privacyIconsController: HeaderPrivacyIconsController,
    private val insetsProvider: StatusBarContentInsetsProvider,
    private val configurationController: ConfigurationController,
    private val variableDateViewControllerFactory: VariableDateViewController.Factory,
    @Named(SHADE_HEADER) private val batteryMeterViewController: BatteryMeterViewController,
    private val dumpManager: DumpManager,
    private val qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder,
    private val combinedShadeHeadersConstraintManager: CombinedShadeHeadersConstraintManager,
    private val demoModeController: DemoModeController,
    private val qsBatteryModeController: QsBatteryModeController,
    private val activityStarter: ActivityStarter
) : ViewController<View>(header), Dumpable {

    companion object {
        /** IDs for transitions and constraints for the [MotionLayout]. */
        @VisibleForTesting internal val HEADER_TRANSITION_ID = R.id.header_transition
        @VisibleForTesting
        internal val LARGE_SCREEN_HEADER_TRANSITION_ID = R.id.large_screen_header_transition
        @VisibleForTesting internal val QQS_HEADER_CONSTRAINT = R.id.qqs_header_constraint
        @VisibleForTesting internal val QS_HEADER_CONSTRAINT = R.id.qs_header_constraint
        @VisibleForTesting
        internal val LARGE_SCREEN_HEADER_CONSTRAINT = R.id.large_screen_header_constraint

        private fun Int.stateToString() =
            when (this) {
                QQS_HEADER_CONSTRAINT -> "QQS Header"
                QS_HEADER_CONSTRAINT -> "QS Header"
                LARGE_SCREEN_HEADER_CONSTRAINT -> "Large Screen Header"
                else -> "Unknown state $this"
            }
    }

    private lateinit var iconManager: StatusBarIconController.TintedIconManager
    private lateinit var carrierIconSlots: List<String>
    private lateinit var qsCarrierGroupController: QSCarrierGroupController

    private val batteryIcon: BatteryMeterView = header.findViewById(R.id.batteryRemainingIcon)
    private val clock: Clock = header.findViewById(R.id.clock)
    private val date: TextView = header.findViewById(R.id.date)
    private val iconContainer: StatusIconContainer = header.findViewById(R.id.statusIcons)
    private val qsCarrierGroup: QSCarrierGroup = header.findViewById(R.id.carrier_group)

    private var roundedCorners = 0
    private var cutout: DisplayCutout? = null
    private var lastInsets: WindowInsets? = null

    private var qsDisabled = false
    private var visible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateListeners()
        }
        whenever(view.visibility).thenAnswer { _ -> viewVisibility }

        whenever(view.setAlpha(anyFloat())).then {
            viewAlpha = it.arguments[0] as Float
            null
        }
        whenever(view.alpha).thenAnswer { _ -> viewAlpha }

        whenever(variableDateViewControllerFactory.create(any()))
            .thenReturn(variableDateViewController)
        whenever(iconManagerFactory.create(any(), any())).thenReturn(iconManager)

        setUpDefaultInsets()
        setUpMotionLayout(view)

        shadeHeaderController =
            ShadeHeaderController(
                view,
                statusBarIconController,
                iconManagerFactory,
                privacyIconsController,
                insetsProvider,
                configurationController,
                variableDateViewControllerFactory,
                batteryMeterViewController,
                dumpManager,
                qsCarrierGroupControllerBuilder,
                combinedShadeHeadersConstraintManager,
                demoModeController,
                qsBatteryModeController,
            )
        whenever(view.isAttachedToWindow).thenReturn(true)
        shadeHeaderController.init()
        carrierIconSlots =
            listOf(context.getString(com.android.internal.R.string.status_bar_mobile))
    }

    @Test
    fun updateListeners_registersWhenVisible() {
        makeShadeVisible()
        verify(qsCarrierGroupController).setListening(true)
        verify(statusBarIconController).addIconGroup(any())
    }

    @Test
    fun statusIconsAddedWhenAttached() {
        verify(statusBarIconController).addIconGroup(any())
    }

    @Test
    fun statusIconsRemovedWhenDettached() {
        shadeHeaderController.simulateViewDetached()
        verify(statusBarIconController).removeIconGroup(any())
    }

    @Test
    fun shadeExpandedFraction_updatesAlpha() {
        makeShadeVisible()
        shadeHeaderController.shadeExpandedFraction = 0.5f
        verify(view).setAlpha(ShadeInterpolation.getContentAlpha(0.5f))
    }

    @Test
    fun singleCarrier_enablesCarrierIconsInStatusIcons() {
        whenever(qsCarrierGroupController.isSingleCarrier).thenReturn(true)

        makeShadeVisible()

        verify(statusIcons).removeIgnoredSlots(carrierIconSlots)
    }

    @Test
    fun dualCarrier_disablesCarrierIconsInStatusIcons() {
        whenever(qsCarrierGroupController.isSingleCarrier).thenReturn(false)

        makeShadeVisible()

    private val configurationControllerListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                val left =
                    header.resources.getDimensionPixelSize(
                        R.dimen.large_screen_shade_header_left_padding
                    )
                header.setPadding(
                    left,
                    header.paddingTop,
                    header.paddingRight,
                    header.paddingBottom
                )
            }

            override fun onDensityOrFontScaleChanged() {
                clock.setTextAppearance(R.style.TextAppearance_QS_Status)
                date.setTextAppearance(R.style.TextAppearance_QS_Status)
                qsCarrierGroup.updateTextAppearance(R.style.TextAppearance_QS_Status_Carriers)
                loadConstraints()
                header.minHeight =
                    resources.getDimensionPixelSize(R.dimen.large_screen_shade_header_min_height)
                lastInsets?.let { updateConstraintsForInsets(header, it) }
                updateResources()
            }
        }

    @Test
    fun disableQS_notDisabled_visible() {
        makeShadeVisible()
        shadeHeaderController.disable(0, 0, false)

        assertThat(viewVisibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun disableQS_disabled_gone() {
        makeShadeVisible()
        shadeHeaderController.disable(0, StatusBarManager.DISABLE2_QUICK_SETTINGS, false)

        assertThat(viewVisibility).isEqualTo(View.GONE)
    }

    private fun makeShadeVisible() {
        shadeHeaderController.largeScreenActive = true
        shadeHeaderController.qsVisible = true
    }

    @Test
    fun updateConfig_changesFontStyle() {
        configurationController.notifyDensityOrFontScaleChanged()

        verify(clock).setTextAppearance(R.style.TextAppearance_QS_Status)
        verify(date).setTextAppearance(R.style.TextAppearance_QS_Status)
        verify(carrierGroup).updateTextAppearance(R.style.TextAppearance_QS_Status_Carriers)
    }

    @Test
    fun animateOutOnStartCustomizing() {
        val animator = mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)

        shadeHeaderController.startCustomizingAnimation(show = true, duration)

        verify(animator).setDuration(duration)
        verify(animator).alpha(0f)
        verify(animator).setInterpolator(Interpolators.ALPHA_OUT)
        verify(animator).start()
    }

    @Test
    fun animateInOnEndCustomizing() {
        val animator = mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)

        shadeHeaderController.startCustomizingAnimation(show = false, duration)

        verify(animator).setDuration(duration)
        verify(animator).alpha(1f)
        verify(animator).setInterpolator(Interpolators.ALPHA_IN)
        verify(animator).start()
    }

    @Test
    fun customizerAnimatorChangesViewVisibility() {
        makeShadeVisible()

        val animator = mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)
        val listenerCaptor = argumentCaptor<Animator.AnimatorListener>()

        shadeHeaderController.startCustomizingAnimation(show = true, duration)
        verify(animator).setListener(capture(listenerCaptor))
        // Start and end the animation
        listenerCaptor.value.onAnimationStart(mock())
        listenerCaptor.value.onAnimationEnd(mock())
        assertThat(viewVisibility).isEqualTo(View.INVISIBLE)

        reset(animator)
        shadeHeaderController.startCustomizingAnimation(show = false, duration)
        verify(animator).setListener(capture(listenerCaptor))
        // Start and end the animation
        listenerCaptor.value.onAnimationStart(mock())
        listenerCaptor.value.onAnimationEnd(mock())
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun animatorListenersClearedAtEnd() {
        val animator = mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        whenever(view.animate()).thenReturn(animator)

        shadeHeaderController.startCustomizingAnimation(show = true, 0L)
        val listenerCaptor = argumentCaptor<Animator.AnimatorListener>()
        verify(animator).setListener(capture(listenerCaptor))

        listenerCaptor.value.onAnimationEnd(mock())
        verify(animator).setListener(null)
    }

    @Test
    fun demoMode_attachDemoMode() {
        val cb = argumentCaptor<DemoMode>()
        verify(demoModeController).addCallback(capture(cb))
        cb.value.onDemoModeStarted()
        verify(clock).onDemoModeStarted()
    }

    @Test
    fun demoMode_detachDemoMode() {
        shadeHeaderController.simulateViewDetached()
        val cb = argumentCaptor<DemoMode>()
        verify(demoModeController).removeCallback(capture(cb))
        cb.value.onDemoModeFinished()
        verify(clock).onDemoModeFinished()
    }

    @Test
    fun testControllersCreatedAndInitialized() {
        verify(variableDateViewController).init()

        verify(batteryMeterViewController).init()
        verify(batteryMeterViewController).ignoreTunerUpdates()

        val inOrder = Mockito.inOrder(qsCarrierGroupControllerBuilder)
        inOrder.verify(qsCarrierGroupControllerBuilder).setQSCarrierGroup(carrierGroup)
        inOrder.verify(qsCarrierGroupControllerBuilder).build()
    }

    @Test
    fun `battery mode controller called when qsExpandedFraction changes`() {
        whenever(qsBatteryModeController.getBatteryMode(Mockito.same(null), eq(0f)))
            .thenReturn(BatteryMeterView.MODE_ON)
        whenever(qsBatteryModeController.getBatteryMode(Mockito.same(null), eq(1f)))
            .thenReturn(BatteryMeterView.MODE_ESTIMATE)
        shadeHeaderController.qsVisible = true

        val times = 10
        repeat(times) { shadeHeaderController.qsExpandedFraction = it / (times - 1).toFloat() }

        verify(batteryMeterView).setPercentShowMode(BatteryMeterView.MODE_ON)
        verify(batteryMeterView).setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
    }

    @Test
    fun testClockPivotLtr() {
        val width = 200
        whenever(clock.width).thenReturn(width)
        whenever(clock.isLayoutRtl).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(clock).addOnLayoutChangeListener(capture(captor))

        captor.value.onLayoutChange(clock, 0, 1, 2, 3, 4, 5, 6, 7)
        verify(clock).pivotX = 0f
    }

    @Test
    fun testClockPivotRtl() {
        val width = 200
        whenever(clock.width).thenReturn(width)
        whenever(clock.isLayoutRtl).thenReturn(true)

        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(clock).addOnLayoutChangeListener(capture(captor))

        captor.value.onLayoutChange(clock, 0, 1, 2, 3, 4, 5, 6, 7)
        verify(clock).pivotX = width.toFloat()
    }

    @Test
    fun testShadeExpanded_true() {
        // When shade is expanded, view should be visible regardless of largeScreenActive
        shadeHeaderController.largeScreenActive = false
        shadeHeaderController.qsVisible = true
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)

        shadeHeaderController.largeScreenActive = true
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testShadeExpanded_false() {
        // When shade is not expanded, view should be invisible regardless of largeScreenActive
        shadeHeaderController.largeScreenActive = false
        shadeHeaderController.qsVisible = false
        assertThat(viewVisibility).isEqualTo(View.INVISIBLE)

        shadeHeaderController.largeScreenActive = true
        assertThat(viewVisibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun testLargeScreenActive_false() {
        shadeHeaderController.largeScreenActive = true // Make sure there's a change
        Mockito.clearInvocations(view)

        shadeHeaderController.largeScreenActive = false

        verify(view).setTransition(ShadeHeaderController.HEADER_TRANSITION_ID)
    }

    @Test
    fun testShadeExpandedFraction() {
        // View needs to be visible for this to actually take effect
        shadeHeaderController.qsVisible = true

        Mockito.clearInvocations(view)
        shadeHeaderController.shadeExpandedFraction = 0.3f
        verify(view).alpha = ShadeInterpolation.getContentAlpha(0.3f)

        Mockito.clearInvocations(view)
        shadeHeaderController.shadeExpandedFraction = 1f
        verify(view).alpha = ShadeInterpolation.getContentAlpha(1f)

        Mockito.clearInvocations(view)
        shadeHeaderController.shadeExpandedFraction = 0f
        verify(view).alpha = ShadeInterpolation.getContentAlpha(0f)
    }

    @Test
    fun testQsExpandedFraction_headerTransition() {
        shadeHeaderController.qsVisible = true
        shadeHeaderController.largeScreenActive = false

        Mockito.clearInvocations(view)
        shadeHeaderController.qsExpandedFraction = 0.3f
        verify(view).progress = 0.3f
    }

    @Test
    fun testQsExpandedFraction_largeScreen() {
        shadeHeaderController.qsVisible = true
        shadeHeaderController.largeScreenActive = true

        Mockito.clearInvocations(view)
        shadeHeaderController.qsExpandedFraction = 0.3f
        verify(view, Mockito.never()).progress = anyFloat()
    }

    @Test
    fun testScrollY_headerTransition() {
        shadeHeaderController.largeScreenActive = false

        Mockito.clearInvocations(view)
        shadeHeaderController.qsScrollY = 20
        verify(view).scrollY = 20
    }

    @Test
    fun testScrollY_largeScreen() {
        shadeHeaderController.largeScreenActive = true

        Mockito.clearInvocations(view)
        shadeHeaderController.qsScrollY = 20
        verify(view, Mockito.never()).scrollY = anyInt()
    }

    @Test
    fun testPrivacyChipVisibilityChanged_visible_changesCorrectConstraints() {
        val chipVisibleChanges = createMockConstraintChanges()
        val chipNotVisibleChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(true))
            .thenReturn(chipVisibleChanges)
        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(false))
            .thenReturn(chipNotVisibleChanges)

        val captor = ArgumentCaptor.forClass(ChipVisibilityListener::class.java)
        verify(privacyIconsController).chipVisibilityListener = capture(captor)

        captor.value.onChipVisibilityRefreshed(true)

        verify(chipVisibleChanges.qqsConstraintsChanges)!!.invoke(qqsConstraints)
        verify(chipVisibleChanges.qsConstraintsChanges)!!.invoke(qsConstraints)
        verify(chipVisibleChanges.largeScreenConstraintsChanges)!!.invoke(largeScreenConstraints)

        verify(chipNotVisibleChanges.qqsConstraintsChanges, Mockito.never())!!.invoke(any())
        verify(chipNotVisibleChanges.qsConstraintsChanges, Mockito.never())!!.invoke(any())
        verify(chipNotVisibleChanges.largeScreenConstraintsChanges, Mockito.never())!!.invoke(any())
    }

    @Test
    fun testPrivacyChipVisibilityChanged_notVisible_changesCorrectConstraints() {
        val chipVisibleChanges = createMockConstraintChanges()
        val chipNotVisibleChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(true))
            .thenReturn(chipVisibleChanges)
        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(false))
            .thenReturn(chipNotVisibleChanges)

        val captor = ArgumentCaptor.forClass(ChipVisibilityListener::class.java)
        verify(privacyIconsController).chipVisibilityListener = capture(captor)

        captor.value.onChipVisibilityRefreshed(false)

        verify(chipVisibleChanges.qqsConstraintsChanges, Mockito.never())!!.invoke(qqsConstraints)
        verify(chipVisibleChanges.qsConstraintsChanges, Mockito.never())!!.invoke(qsConstraints)
        verify(chipVisibleChanges.largeScreenConstraintsChanges, Mockito.never())!!.invoke(
            largeScreenConstraints
        )

        verify(chipNotVisibleChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(chipNotVisibleChanges.qsConstraintsChanges)!!.invoke(any())
        verify(chipNotVisibleChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testInsetsGuides_ltr() {
        whenever(view.isLayoutRtl).thenReturn(false)
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        val (insetLeft, insetRight) = 30 to 40
        val (paddingStart, paddingEnd) = 10 to 20
        whenever(view.paddingStart).thenReturn(paddingStart)
        whenever(view.paddingEnd).thenReturn(paddingEnd)

        mockInsetsProvider(insetLeft to insetRight, false)

        whenever(
                combinedShadeHeadersConstraintManager.edgesGuidelinesConstraints(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyInt()
                )
            )
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager)
            .edgesGuidelinesConstraints(insetLeft, paddingStart, insetRight, paddingEnd)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testInsetsGuides_rtl() {
        whenever(view.isLayoutRtl).thenReturn(true)
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        val (insetLeft, insetRight) = 30 to 40
        val (paddingStart, paddingEnd) = 10 to 20
        whenever(view.paddingStart).thenReturn(paddingStart)
        whenever(view.paddingEnd).thenReturn(paddingEnd)

        mockInsetsProvider(insetLeft to insetRight, false)

        whenever(
                combinedShadeHeadersConstraintManager.edgesGuidelinesConstraints(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyInt()
                )
            )
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager)
            .edgesGuidelinesConstraints(insetRight, paddingStart, insetLeft, paddingEnd)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testNullCutout() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(null))

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, Mockito.never())
            .centerCutoutConstraints(Mockito.anyBoolean(), anyInt())

        dumpManager.registerDumpable(this)
        configurationController.addCallback(configurationControllerListener)
        demoModeController.addCallback(demoModeReceiver)
        statusBarIconController.addIconGroup(iconManager)
    }

    override fun onViewDetached() {
        privacyIconsController.chipVisibilityListener = null
        dumpManager.unregisterDumpable(this::class.java.simpleName)
        configurationController.removeCallback(configurationControllerListener)
        demoModeController.removeCallback(demoModeReceiver)
        statusBarIconController.removeIconGroup(iconManager)
    }

    fun disable(state1: Int, state2: Int, animate: Boolean) {
        val disabled = state2 and StatusBarManager.DISABLE2_QUICK_SETTINGS != 0
        if (disabled == qsDisabled) return
        qsDisabled = disabled
        updateVisibility()
    }

    fun startCustomizingAnimation(show: Boolean, duration: Long) {
        header
            .animate()
            .setDuration(duration)
            .alpha(if (show) 0f else 1f)
            .setInterpolator(if (show) Interpolators.ALPHA_OUT else Interpolators.ALPHA_IN)
            .setListener(CustomizerAnimationListener(show))
            .start()
    }

    private fun loadConstraints() {
        // Use resources.getXml instead of passing the resource id due to bug b/205018300
        header
            .getConstraintSet(QQS_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.qqs_header))
        header
            .getConstraintSet(QS_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.qs_header))
        header
            .getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT)
            .load(context, resources.getXml(R.xml.large_screen_shade_header))
    }

    private fun updateConstraintsForInsets(view: MotionLayout, insets: WindowInsets) {
        val cutout = insets.displayCutout.also { this.cutout = it }

        val sbInsets: Pair<Int, Int> = insetsProvider.getStatusBarContentInsetsForCurrentRotation()
        val cutoutLeft = sbInsets.first
        val cutoutRight = sbInsets.second
        val hasCornerCutout: Boolean = insetsProvider.currentRotationHasCornerCutout()
        updateQQSPaddings()
        // Set these guides as the left/right limits for content that lives in the top row, using
        // cutoutLeft and cutoutRight
        var changes =
            combinedShadeHeadersConstraintManager.edgesGuidelinesConstraints(
                if (view.isLayoutRtl) cutoutRight else cutoutLeft,
                header.paddingStart,
                if (view.isLayoutRtl) cutoutLeft else cutoutRight,
                header.paddingEnd
            )

    @Test
    fun testEmptyCutout() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, Mockito.never())
            .centerCutoutConstraints(Mockito.anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testCornerCutout_emptyRect() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, true)

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, Mockito.never())
            .centerCutoutConstraints(Mockito.anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testCornerCutout_nonEmptyRect() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, true)

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(Rect(1, 2, 3, 4)))

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, Mockito.never())
            .centerCutoutConstraints(Mockito.anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testTopCutout_ltr() {
        val width = 100
        val paddingLeft = 10
        val paddingRight = 20
        val cutoutWidth = 30

        whenever(view.isLayoutRtl).thenReturn(false)
        whenever(view.width).thenReturn(width)
        whenever(view.paddingLeft).thenReturn(paddingLeft)
        whenever(view.paddingRight).thenReturn(paddingRight)

        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, false)

        whenever(
                combinedShadeHeadersConstraintManager.centerCutoutConstraints(
                    Mockito.anyBoolean(),
                    anyInt()
                )
            )
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(Rect(0, 0, cutoutWidth, 1)))

        verify(combinedShadeHeadersConstraintManager, Mockito.never()).emptyCutoutConstraints()
        val offset = (width - paddingLeft - paddingRight - cutoutWidth) / 2
        verify(combinedShadeHeadersConstraintManager).centerCutoutConstraints(false, offset)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testTopCutout_rtl() {
        val width = 100
        val paddingLeft = 10
        val paddingRight = 20
        val cutoutWidth = 30

        whenever(view.isLayoutRtl).thenReturn(true)
        whenever(view.width).thenReturn(width)
        whenever(view.paddingLeft).thenReturn(paddingLeft)
        whenever(view.paddingRight).thenReturn(paddingRight)

        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, false)

        whenever(
                combinedShadeHeadersConstraintManager.centerCutoutConstraints(
                    Mockito.anyBoolean(),
                    anyInt()
                )
            )
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(Rect(0, 0, cutoutWidth, 1)))

        verify(combinedShadeHeadersConstraintManager, Mockito.never()).emptyCutoutConstraints()
        val offset = (width - paddingLeft - paddingRight - cutoutWidth) / 2
        verify(combinedShadeHeadersConstraintManager).centerCutoutConstraints(true, offset)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun alarmIconNotIgnored() {
        verify(statusIcons, Mockito.never())
            .addIgnoredSlot(context.getString(com.android.internal.R.string.status_bar_alarm_clock))
    }

    @Test
    fun privacyChipParentVisibleFromStart() {
        verify(privacyIconsController).onParentVisible()
    }

    @Test
    fun privacyChipParentVisibleAlways() {
        shadeHeaderController.largeScreenActive = true
        shadeHeaderController.largeScreenActive = false
        shadeHeaderController.largeScreenActive = true

        verify(privacyIconsController, Mockito.never()).onParentInvisible()
    }

    @Test
    fun clockPivotYInCenter() {
        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(clock).addOnLayoutChangeListener(capture(captor))
        var height = 100
        val width = 50

        clock.executeLayoutChange(0, 0, width, height, captor.value)
        verify(clock).pivotY = height.toFloat() / 2

        height = 150
        clock.executeLayoutChange(0, 0, width, height, captor.value)
        verify(clock).pivotY = height.toFloat() / 2
    }

    private fun updateResources() {
        roundedCorners = resources.getDimensionPixelSize(R.dimen.rounded_corner_content_padding)
        val padding = resources.getDimensionPixelSize(R.dimen.qs_panel_padding)
        header.setPadding(padding, header.paddingTop, padding, header.paddingBottom)
        updateQQSPaddings()
        qsBatteryModeController.updateResources()
    }

    private fun View.executeLayoutChange(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        listener: View.OnLayoutChangeListener
    ) {
        val oldLeft = this.left
        val oldTop = this.top
        val oldRight = this.right
        val oldBottom = this.bottom
        whenever(this.left).thenReturn(left)
        whenever(this.top).thenReturn(top)
        whenever(this.right).thenReturn(right)
        whenever(this.bottom).thenReturn(bottom)
        whenever(this.height).thenReturn(bottom - top)
        whenever(this.width).thenReturn(right - left)
        listener.onLayoutChange(
            this,
            oldLeft,
            oldTop,
            oldRight,
            oldBottom,
            left,
            top,
            right,
            bottom
        )
    }

    private fun createWindowInsets(topCutout: Rect? = Rect()): WindowInsets {
        val windowInsets: WindowInsets = mock()
        val displayCutout: DisplayCutout = mock()
        whenever(windowInsets.displayCutout)
            .thenReturn(if (topCutout != null) displayCutout else null)
        whenever(displayCutout.boundingRectTop).thenReturn(topCutout)

        return windowInsets
    }

    private fun mockInsetsProvider(
        insets: Pair<Int, Int> = 0 to 0,
        cornerCutout: Boolean = false,
    ) {
        whenever(insetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(insets.toAndroidPair())
        whenever(insetsProvider.currentRotationHasCornerCutout()).thenReturn(cornerCutout)
    }

    private fun createMockConstraintChanges(): ConstraintsChanges {
        return ConstraintsChanges(mock(), mock(), mock())
    }

    private fun XmlResourceParser.getResId(): Int {
        return Resources.getAttributeSetSourceResId(this)
    }

    private fun setUpMotionLayout(motionLayout: MotionLayout) {
        whenever(motionLayout.getConstraintSet(QQS_HEADER_CONSTRAINT)).thenReturn(qqsConstraints)
        whenever(motionLayout.getConstraintSet(QS_HEADER_CONSTRAINT)).thenReturn(qsConstraints)
        whenever(motionLayout.getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT))
            .thenReturn(largeScreenConstraints)
    }

    private fun setUpDefaultInsets() {
        whenever(
                combinedShadeHeadersConstraintManager.edgesGuidelinesConstraints(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyInt()
                )
            )
            .thenReturn(EMPTY_CHANGES)
        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(EMPTY_CHANGES)
        whenever(
                combinedShadeHeadersConstraintManager.centerCutoutConstraints(
                    Mockito.anyBoolean(),
                    anyInt()
                )
            )
            .thenReturn(EMPTY_CHANGES)
        whenever(
                combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(
                    Mockito.anyBoolean()
                )
            )
            .thenReturn(EMPTY_CHANGES)
        whenever(insetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(Pair(0, 0).toAndroidPair())
        whenever(insetsProvider.currentRotationHasCornerCutout()).thenReturn(false)
        setupCurrentInsets(null)
    }

    private fun setupCurrentInsets(cutout: DisplayCutout?) {
        val mockedDisplay =
            mock<Display>().also { display -> whenever(display.cutout).thenReturn(cutout) }
        whenever(viewContext.display).thenReturn(mockedDisplay)
    }

    private fun <T, U> Pair<T, U>.toAndroidPair(): android.util.Pair<T, U> {
        return android.util.Pair(first, second)
    }
}