/**
 * Family Room Light Automation
 *
 * Hardware
 *   - GE Enbrighten Z-Wave Smart Dimmer, Model 18756  (this IS the light)
 *   - SmartThings Motion Sensor, 2018, SamJin/Samsung  (Zigbee)
 *   - Virtual Switch "Family Room Lights Alexa"        (Alexa ON/OFF override signal)
 *   - Virtual Switch "Family Room Movie Mode"          (Alexa Movie Mode toggle)
 *
 * ─── STATE MACHINE ───────────────────────────────────────────────────────────
 *
 *   NORMAL           Motion drives the light.
 *                    Daytime (hub modes or sunrise/sunset) = 100%, after sunset = 20%.
 *                    Off-timer = MOTION_TIMEOUT_SEC, counted from last ACTIVE event (Q15).
 *
 *   TEMP_OVERRIDE    Entered by single-press ON/OFF (physical or Alexa).
 *                    If ON : 30-min no-motion hold from last ACTIVE, then OFF → NORMAL.
 *                    If OFF: lights stay off; motion cannot turn them on.
 *                    Absolute ceiling: 2 hr → NORMAL.
 *
 *   LONG_OVERRIDE    Entered by double-press ON.
 *                    Lights forced ON; motion completely ignored.
 *                    Ceiling: 8 hr. Manual single-press OFF exits → TEMP_OVERRIDE.
 *
 *   MOTION_DISABLED  Entered by double-press OFF.
 *                    Motion completely ignored; manual ON/OFF still work.
 *                    Ceiling: 4 hr → NORMAL.
 *
 *   MOVIE_MODE       Entered by Alexa Movie Mode virtual switch ON.
 *                    Lights OFF immediately; motion ignored.
 *                    Exit: Alexa switch OFF, any physical button press, or 4-hr ceiling.
 *
 * ─── PRIORITY (highest wins on entry) ────────────────────────────────────────
 *   MOVIE_MODE > LONG_OVERRIDE > MOTION_DISABLED > TEMP_OVERRIDE > NORMAL
 *   cancelAllTimers() is called at the top of every state transition — no stale
 *   timers can survive a state change.
 *
 * ─── REBOOT BEHAVIOR (Q10) ───────────────────────────────────────────────────
 *   Transient state (currentState, motion timestamps, flags) resets to NORMAL.
 *   lastManualLevel persists across reboots (Q14); cleared only by explicit OFF.
 *
 * ─── DAYTIME DEFINITION (Q1/Q3) ──────────────────────────────────────────────
 *   If daytimeModes is configured: daytime = hub is currently in one of those modes.
 *   Otherwise (default): daytime = now is after local sunrise AND before local sunset.
 *
 * ─── MOTION TIMEOUT COUNTING (Q15) ───────────────────────────────────────────
 *   Timer counts from last ACTIVE event.  If sensor goes inactive 4 min after last
 *   active, only 1 min remains on the 5-min timer, not a full fresh 5 min.
 *
 * ─── ALEXA VIRTUAL SWITCHES ──────────────────────────────────────────────────
 *   Standard virtual switches are kept in sync with the app's state to prevent
 *   voice control lockups. A syncing flag prevents write-backs from re-triggering
 *   event handlers (feedback loop guard).
 *
 * Author  : Evan
 * Version : 1.1.2
 * Date    : 2026-10-12
 */

import groovy.transform.Field

// =============================================================================
// CONSTANTS — every duration and magic number lives here only
// =============================================================================

@Field static final String STATE_NORMAL          = "NORMAL"
@Field static final String STATE_TEMP_OVERRIDE   = "TEMP_OVERRIDE"
@Field static final String STATE_LONG_OVERRIDE   = "LONG_OVERRIDE"
@Field static final String STATE_MOTION_DISABLED = "MOTION_DISABLED"
@Field static final String STATE_MOVIE_MODE      = "MOVIE_MODE"

@Field static final int LEVEL_DAYTIME            = 100   // percent
@Field static final int LEVEL_NIGHTTIME          = 20    // percent

// All durations in seconds.
@Field static final int MOTION_TIMEOUT_SEC       = 300   //  5 min — from last ACTIVE event
@Field static final int TEMP_WINDOW_SEC          = 1800  // 30 min — no-motion hold in TEMP_OVERRIDE
@Field static final int TEMP_EXPIRY_SEC          = 7200  //  2 hr  — TEMP_OVERRIDE absolute ceiling
@Field static final int LONG_OVERRIDE_SEC        = 28800 //  8 hr  — LONG_OVERRIDE ceiling
@Field static final int MOTION_DISABLED_SEC      = 14400 //  4 hr  — MOTION_DISABLED ceiling
@Field static final int MOVIE_MODE_SEC           = 14400 //  4 hr  — MOVIE_MODE ceiling
@Field static final int DEBUG_AUTO_OFF_SEC       = 1800  // 30 min — revert Debug→Info
@Field static final int FLAG_CLEAR_DELAY_SEC     = 5     //  5 sec — settle time for Z-Wave ramp and virtual switches

// GE Enbrighten 18756 button numbers as reported by the Hubitat built-in driver.
//   pushed,       value "1" = top paddle single press   → ON
//   pushed,       value "2" = bottom paddle single press → OFF
//   doubleTapped, value "1" = top paddle double press   → LONG_OVERRIDE
//   doubleTapped, value "2" = bottom paddle double press → MOTION_DISABLED
//
// ⚠ In Device Preferences, "Enable Double-Tap" MUST be ON or doubleTapped never fires.
@Field static final String BTN_TOP    = "1"
@Field static final String BTN_BOTTOM = "2"

// =============================================================================
// DEFINITION
// =============================================================================

definition(
    name:        "Family Room Light Automation",
    namespace:   "evan.home",
    author:      "Evan",
    description: "Automates Family Room lights: motion, dimmer overrides, Alexa commands.",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   ""
)

// =============================================================================
// PREFERENCES
// =============================================================================

preferences {
    section("Devices") {
        input name: "dimmer",
              type: "capability.switchLevel",
              title: "Family Room Dimmer / Light  (GE Enbrighten 18756)",
              required: true, multiple: false

        input name: "motionSensor",
              type: "capability.motionSensor",
              title: "Family Room Motion Sensor  (SmartThings 2018)",
              required: true, multiple: false

        input name: "alexaLightSwitch",
              type: "capability.switch",
              title: "Alexa Virtual Switch — Lights  (\"Family Room Lights Alexa\")",
              required: true, multiple: false,
              description: "Alexa turns this on/off; app translates it into an override command"

        input name: "alexaMovieSwitch",
              type: "capability.switch",
              title: "Alexa Virtual Switch — Movie Mode  (\"Family Room Movie Mode\")",
              required: true, multiple: false,
              description: "Alexa turns this on/off to enter/exit Movie Mode"
    }

    section("Daytime Definition  (leave blank to use local sunrise→sunset automatically)") {
        input name: "daytimeModes",
              type: "mode",
              title: "Hubitat Mode(s) that count as Daytime  → lights at 100%",
              required: false, multiple: true,
              description: "When unset the app uses hub-location sunrise/sunset"
    }

    section("Logging") {
        input name: "logLevel",
              type: "enum",
              title: "Log Level",
              options: ["None", "Info", "Debug"],
              defaultValue: "Info",
              required: true

        input name: "debugAutoOff",
              type: "bool",
              title: "Auto-revert Debug → Info after 30 minutes",
              defaultValue: true,
              required: true
    }

    section("Testing Overrides  (set each to 0 to use the real production duration)") {
        input name: "testMotionTimeoutSec",
              type: "number", defaultValue: 0, required: false,
              title: "Motion off-timer override                (real: ${MOTION_TIMEOUT_SEC}s)"
        input name: "testTempWindowSec",
              type: "number", defaultValue: 0, required: false,
              title: "Temp override no-motion window override   (real: ${TEMP_WINDOW_SEC}s)"
        input name: "testTempExpirySec",
              type: "number", defaultValue: 0, required: false,
              title: "Temp override absolute expiry override    (real: ${TEMP_EXPIRY_SEC}s)"
        input name: "testLongOverrideSec",
              type: "number", defaultValue: 0, required: false,
              title: "Long override duration override           (real: ${LONG_OVERRIDE_SEC}s)"
        input name: "testMotionDisabledSec",
              type: "number", defaultValue: 0, required: false,
              title: "Motion-disabled duration override         (real: ${MOTION_DISABLED_SEC}s)"
        input name: "testMovieModeSec",
              type: "number", defaultValue: 0, required: false,
              title: "Movie Mode duration override              (real: ${MOVIE_MODE_SEC}s)"
    }
}

// =============================================================================
// LIFECYCLE
// =============================================================================

def installed() {
    logInfo "App installed."
    // lastManualLevel is intentionally absent (null) on fresh install.
    initialize()
}

def updated() {
    logInfo "App updated — reinitializing."
    unsubscribe()
    cancelAllTimers()
    initialize()
}

def initialize() {
    logInfo "initialize() — resetting transient state to NORMAL."

    // All transient runtime fields reset on every init (including after reboot).
    // lastManualLevel is deliberately excluded — it survives reboots (Q14).
    resetTransientState()

    // ── Subscriptions ─────────────────────────────────────────────────────────
    // Physical dimmer button events (for drivers reporting pushed / doubleTapped)
    subscribe(dimmer, "pushed",           "onButtonPushed")
    subscribe(dimmer, "doubleTapped",     "onButtonDoubleTapped")

    // Physical dimmer switch events (for built-in drivers reporting switch.on/off with isPhysical)
    subscribe(dimmer, "switch.on",        "onDimmerSwitchOn")
    subscribe(dimmer, "switch.off",       "onDimmerSwitchOff")

    // Dimmer level changes — to memorize manual dim adjustments
    subscribe(dimmer, "level",            "onLevelChanged")

    // Motion sensor
    subscribe(motionSensor, "motion.active",   "onMotionActive")
    subscribe(motionSensor, "motion.inactive", "onMotionInactive")

    // Alexa virtual switches
    subscribe(alexaLightSwitch, "switch.on",  "onAlexaLightOn")
    subscribe(alexaLightSwitch, "switch.off", "onAlexaLightOff")
    subscribe(alexaMovieSwitch, "switch.on",  "onAlexaMovieOn")
    subscribe(alexaMovieSwitch, "switch.off", "onAlexaMovieOff")

    // Auto-disable debug logging
    if (logLevel == "Debug" && debugAutoOff) {
        runIn(DEBUG_AUTO_OFF_SEC, "disableDebugLogging")
    }

    logInfo "Initialization complete. currentState=${state.currentState}"
}

// =============================================================================
// STATE INITIALIZATION
// =============================================================================

/**
 * Resets all transient runtime fields to their initial values.
 * Called on every initialize(), so hub reboots always land in NORMAL (Q10).
 *
 * state.lastManualLevel is intentionally NOT reset here (Q14).
 */
private void resetTransientState() {
    state.currentState         = STATE_NORMAL
    state.overrideIsOn         = false  // Boolean: true if TEMP_OVERRIDE was entered via ON, false if via OFF
    state.lastMotionActiveTime = null   // Long   : epoch ms of last motion-active event
    state.overrideStartTime    = null   // Long   : epoch ms when current override began
    atomicState.appSettingLevel    = false  // Boolean: true while app is issuing setLevel()
    atomicState.appTurningOff      = false  // Boolean: true while app is issuing off()
    atomicState.syncingLightSwitch = false  // Boolean: true while app syncs Light vswitch back to Alexa
    atomicState.syncingMovieSwitch = false  // Boolean: true while app syncs Movie Mode vswitch back to Alexa
    state.lastPhysicalOnTime   = 0L     // Long   : debounce timestamp for physical paddle ON
    state.lastPhysicalOffTime  = 0L     // Long   : debounce timestamp for physical paddle OFF
    logDebug "Transient state reset. lastManualLevel retained: ${state.lastManualLevel}"
}

// =============================================================================
// TIMER UTILITIES
// =============================================================================

/**
 * Cancels every named scheduled job managed by this app.
 *
 * *** MUST be called at the start of every state transition. ***
 *
 * This is the single invariant that guarantees no stale timer from a prior
 * state fires after the state machine has moved on.
 */
private void cancelAllTimers() {
    unschedule("motionTimeoutHandler")
    unschedule("tempOverrideWindowHandler")
    unschedule("tempOverrideExpiryHandler")
    unschedule("longOverrideExpiryHandler")
    unschedule("motionDisabledExpiryHandler")
    unschedule("movieModeExpiryHandler")
    // Note: disableDebugLogging and flag clearers are intentionally NOT canceled here.
    logDebug "All state-machine timers canceled."
}

/**
 * Returns the effective duration in seconds, using the test override when
 * it is set to a positive value, otherwise the real production constant.
 */
private int effectiveSec(int realSec, def testSec) {
    int t = (testSec ?: 0) as int
    return (t > 0) ? t : realSec
}

/**
 * Computes the number of seconds to delay the motion off-timer, counting
 * from the last ACTIVE event (Q15).
 *
 * Example: motion active at T=0, sensor goes inactive at T=4min.
 *          With a 5-min timeout, only 1 min should remain — not a fresh 5 min.
 *
 * Returns at least 1 second (runIn(0) is unreliable on Hubitat).
 */
private int computeMotionDelaySec(int fullTimeoutSec) {
    if (!state.lastMotionActiveTime) {
        logDebug "computeMotionDelaySec: no lastMotionActiveTime recorded — using full ${fullTimeoutSec}s."
        return fullTimeoutSec
    }
    long elapsedMs  = now() - (state.lastMotionActiveTime as long)
    long elapsedSec = elapsedMs / 1000L
    long remaining  = (fullTimeoutSec as long) - elapsedSec
    int  delaySec   = (remaining > 1L) ? (remaining as int) : 1
    logDebug "computeMotionDelaySec: elapsed=${elapsedSec}s / full=${fullTimeoutSec}s → scheduling in ${delaySec}s."
    return delaySec
}

// =============================================================================
// MOTION EVENT HANDLERS
// =============================================================================

def onMotionActive(evt) {
    logDebug "onMotionActive() — currentState=${state.currentState}"

    // Always record the timestamp regardless of state — needed by computeMotionDelaySec().
    state.lastMotionActiveTime = now()

    switch (state.currentState) {

        case STATE_NORMAL:
            // Cancel any pending off-timer; motion is active again.
            unschedule("motionTimeoutHandler")
            // Some dimmers do not reliably report physical state changes. Always send the
            // idempotent level command rather than trusting a potentially stale cached state.
            turnOnToDefaultLevel()
            break

        case STATE_TEMP_OVERRIDE:
            if (state.overrideIsOn) {
                // Motion returned during the no-motion window — cancel that window timer.
                // Light state is NOT changed here; the override governs it.
                unschedule("tempOverrideWindowHandler")
                logInfo "Motion returned in TEMP_OVERRIDE (ON) — no-motion window canceled."
            } else {
                // Override is OFF: motion cannot turn lights on.
                logDebug "Motion active suppressed in TEMP_OVERRIDE (OFF) — lights remain off."
            }
            break

        case STATE_LONG_OVERRIDE:
        case STATE_MOTION_DISABLED:
        case STATE_MOVIE_MODE:
            logDebug "Motion active suppressed — currentState=${state.currentState}."
            break

        default:
            logWarn "onMotionActive: unrecognized state '${state.currentState}' — doing nothing."
    }
}

def onMotionInactive(evt) {
    logDebug "onMotionInactive() — currentState=${state.currentState}"

    switch (state.currentState) {

        case STATE_NORMAL:
            int delaySec = computeMotionDelaySec(
                               effectiveSec(MOTION_TIMEOUT_SEC, testMotionTimeoutSec))
            logInfo "Motion inactive (NORMAL) — scheduling off-timer in ${delaySec}s."
            runIn(delaySec, "motionTimeoutHandler")
            break

        case STATE_TEMP_OVERRIDE:
            if (state.overrideIsOn) {
                // Start the 30-min no-motion window counted from last ACTIVE event.
                int windowSec = effectiveSec(TEMP_WINDOW_SEC, testTempWindowSec)
                int delaySec  = computeMotionDelaySec(windowSec)
                logInfo "Motion inactive (TEMP_OVERRIDE ON) — scheduling no-motion window in ${delaySec}s."
                runIn(delaySec, "tempOverrideWindowHandler")
            } else {
                logDebug "Motion inactive in TEMP_OVERRIDE (OFF) — suppressed."
            }
            break

        case STATE_LONG_OVERRIDE:
        case STATE_MOTION_DISABLED:
        case STATE_MOVIE_MODE:
            logDebug "Motion inactive suppressed — currentState=${state.currentState}."
            break

        default:
            logWarn "onMotionInactive: unrecognized state '${state.currentState}' — doing nothing."
    }
}

// =============================================================================
// PHYSICAL BUTTON & SWITCH HANDLERS — GE Enbrighten 18756
// =============================================================================

def onButtonPushed(evt) {
    String btn = evt.value?.toString()
    logDebug "onButtonPushed(${btn}) — currentState=${state.currentState}"

    long nowMs = now()
    switch (btn) {
        case BTN_TOP:
            if (state.lastPhysicalOnTime && (nowMs - (state.lastPhysicalOnTime as long)) < 1500) {
                logDebug "onButtonPushed: debouncing duplicate ON event."
                return
            }
            state.lastPhysicalOnTime = nowMs
            handleSinglePressOn("physical")
            break

        case BTN_BOTTOM:
            if (state.lastPhysicalOffTime && (nowMs - (state.lastPhysicalOffTime as long)) < 1500) {
                logDebug "onButtonPushed: debouncing duplicate OFF event."
                return
            }
            state.lastPhysicalOffTime = nowMs
            handleSinglePressOff("physical")
            break

        default:
            logWarn "onButtonPushed: unexpected buttonNumber '${btn}' — ignoring."
    }
}

def onButtonDoubleTapped(evt) {
    String btn = evt.value?.toString()
    logDebug "onButtonDoubleTapped(${btn}) — currentState=${state.currentState}"

    switch (btn) {
        case BTN_TOP:    handleDoublePressOn();  break
        case BTN_BOTTOM: handleDoublePressOff(); break
        default: logWarn "onButtonDoubleTapped: unexpected buttonNumber '${btn}' — ignoring."
    }
}

def onDimmerSwitchOn(evt) {
    logDebug "onDimmerSwitchOn: type=${evt.type}, isPhysical=${evt.isPhysical()}, appSettingLevel=${atomicState.appSettingLevel}"
    if (atomicState.appSettingLevel) {
        return
    }
    // Process physical paddle taps for drivers reporting switch events instead of pushed events
    if (evt.isPhysical() || evt.type == "physical") {
        long nowMs = now()
        if (state.lastPhysicalOnTime && (nowMs - (state.lastPhysicalOnTime as long)) < 1500) {
            logDebug "onDimmerSwitchOn: debouncing duplicate physical ON event."
            return
        }
        state.lastPhysicalOnTime = nowMs
        logInfo "Physical switch ON detected via switch event."
        handleSinglePressOn("physical")
    }
}

def onDimmerSwitchOff(evt) {
    logDebug "onDimmerSwitchOff: type=${evt.type}, isPhysical=${evt.isPhysical()}, appTurningOff=${atomicState.appTurningOff}"
    if (atomicState.appTurningOff) {
        return
    }
    if (evt.isPhysical() || evt.type == "physical") {
        long nowMs = now()
        if (state.lastPhysicalOffTime && (nowMs - (state.lastPhysicalOffTime as long)) < 1500) {
            logDebug "onDimmerSwitchOff: debouncing duplicate physical OFF event."
            return
        }
        state.lastPhysicalOffTime = nowMs
        logInfo "Physical switch OFF detected via switch event."
        handleSinglePressOff("physical")
    }
}

// =============================================================================
// ALEXA VIRTUAL SWITCH HANDLERS
// =============================================================================

def onAlexaLightOn(evt) {
    if (atomicState.syncingLightSwitch) {
        logDebug "onAlexaLightOn: sync in progress — skipping."
        return
    }
    logDebug "onAlexaLightOn() — currentState=${state.currentState}"
    handleSinglePressOn("alexa")
}

def onAlexaLightOff(evt) {
    if (atomicState.syncingLightSwitch) {
        logDebug "onAlexaLightOff: sync in progress — skipping."
        return
    }
    logDebug "onAlexaLightOff() — currentState=${state.currentState}"
    handleSinglePressOff("alexa")
}

def onAlexaMovieOn(evt) {
    if (atomicState.syncingMovieSwitch) {
        logDebug "onAlexaMovieOn: sync in progress — skipping."
        return
    }
    logInfo "Alexa → Movie Mode ON"
    enterMovieMode()
}

def onAlexaMovieOff(evt) {
    if (atomicState.syncingMovieSwitch) {
        logDebug "onAlexaMovieOff: sync in progress — skipping."
        return
    }
    logInfo "Alexa → Movie Mode OFF"
    exitMovieMode()
}

// =============================================================================
// LEVEL CHANGE HANDLER — memorize manual dim level adjustments
// =============================================================================

def onLevelChanged(evt) {
    // This fires for EVERY level change, including those the app itself issues.
    // The appSettingLevel flag distinguishes app-driven from user-driven changes.
    if (atomicState.appSettingLevel) {
        logDebug "onLevelChanged: app-initiated level ${evt.value}% — not memorizing."
        return
    }

    int level = (evt.value as Integer) ?: 0
    if (level > 0) {
        // Positive level = lights are on and user has set a new brightness.
        state.lastManualLevel = level
        logInfo "Manual dim level memorized: ${level}%"
    }
    // level == 0 means lights are turning off; we do not memorize 0.
}

// =============================================================================
// SHARED COMMAND LOGIC — called by both physical and Alexa handlers
// =============================================================================

/**
 * Single-press ON — from physical dimmer or Alexa.
 *
 * Behavior:
 *   - MOVIE_MODE: physical press exits Movie Mode (Q13) & syncs Movie switch OFF.
 *                 Alexa light ON is ignored (MOVIE_MODE > TEMP_OVERRIDE).
 *   - MOTION_DISABLED: turns lights ON, but motion remains disabled for remainder of 4 hr.
 *   - LONG_OVERRIDE: lights are already forced ON, do not downgrade to TEMP_OVERRIDE.
 *   - NORMAL / TEMP_OVERRIDE: enters TEMP_OVERRIDE (ON) with 30-min no-motion hold & 2-hr ceiling.
 *
 * @param source  "physical" or "alexa"
 */
private void handleSinglePressOn(String source) {
    logInfo "Single-press ON (${source}) — from state ${state.currentState}."

    // 1. Movie Mode handling
    if (state.currentState == STATE_MOVIE_MODE) {
        if (source == "physical") {
            logInfo "Physical press exits Movie Mode → TEMP_OVERRIDE (ON)."
            syncMovieSwitchOff()
        } else {
            logInfo "Alexa light ON ignored while in MOVIE_MODE (MOVIE_MODE has priority)."
            return
        }
    }

    // 2. Motion Disabled handling: manual ON works without killing the 4-hr motion disable
    if (state.currentState == STATE_MOTION_DISABLED) {
        logInfo "Single-press ON in MOTION_DISABLED — turning lights ON; motion remains disabled."
        int lvl = (state.lastManualLevel != null) ? (state.lastManualLevel as int) : defaultLevel()
        setDimmerLevel(lvl)
        return
    }

    // 3. Long Override handling: already forced ON; do not downgrade to TEMP_OVERRIDE
    if (state.currentState == STATE_LONG_OVERRIDE) {
        logInfo "Single-press ON in LONG_OVERRIDE — ensuring lights ON; retaining LONG_OVERRIDE."
        int lvl = (state.lastManualLevel != null) ? (state.lastManualLevel as int) : defaultLevel()
        setDimmerLevel(lvl)
        return
    }

    // 4. Standard transition to TEMP_OVERRIDE (ON)
    cancelAllTimers()
    state.currentState      = STATE_TEMP_OVERRIDE
    state.overrideIsOn      = true
    state.overrideStartTime = now()

    int expirySec = effectiveSec(TEMP_EXPIRY_SEC, testTempExpirySec)
    runIn(expirySec, "tempOverrideExpiryHandler")
    logInfo "TEMP_OVERRIDE entered (ON). Expiry in ${expirySec}s."

    int level = (state.lastManualLevel != null) ? (state.lastManualLevel as int) : defaultLevel()
    setDimmerLevel(level)

    // If motion is ALREADY inactive right now, schedule the 30-min window immediately
    def motionValue = motionSensor?.currentValue("motion")
    if (motionValue != "active") {
        int windowSec = effectiveSec(TEMP_WINDOW_SEC, testTempWindowSec)
        int delaySec  = computeMotionDelaySec(windowSec)
        logInfo "TEMP_OVERRIDE (ON) entered while motion inactive — scheduling no-motion window in ${delaySec}s."
        runIn(delaySec, "tempOverrideWindowHandler")
    }
}

/**
 * Single-press OFF — from physical dimmer or Alexa.
 *
 * Behavior:
 *   - MOVIE_MODE: physical press exits Movie Mode (Q13) & syncs Movie switch OFF.
 *                 Alexa light OFF is ignored (lights are already off).
 *   - MOTION_DISABLED: turns lights OFF & clears lastManualLevel, motion remains disabled.
 *   - LONG_OVERRIDE: exits to TEMP_OVERRIDE (OFF).
 *   - NORMAL / TEMP_OVERRIDE: enters TEMP_OVERRIDE (OFF), clears lastManualLevel, 2-hr ceiling.
 *
 * @param source  "physical" or "alexa"
 */
private void handleSinglePressOff(String source) {
    logInfo "Single-press OFF (${source}) — from state ${state.currentState}."

    // 1. Movie Mode handling
    if (state.currentState == STATE_MOVIE_MODE) {
        if (source == "physical") {
            logInfo "Physical press exits Movie Mode → TEMP_OVERRIDE (OFF)."
            syncMovieSwitchOff()
        } else {
            logInfo "Alexa light OFF ignored while in MOVIE_MODE."
            return
        }
    }

    // 2. Motion Disabled handling: manual OFF works without killing the 4-hr motion disable
    if (state.currentState == STATE_MOTION_DISABLED) {
        logInfo "Single-press OFF in MOTION_DISABLED — turning lights OFF; motion remains disabled."
        state.lastManualLevel = null
        turnDimmerOff()
        return
    }

    // 3. Clear remembered level on explicit manual OFF (Q14 spec)
    cancelAllTimers()
    state.lastManualLevel   = null
    state.currentState      = STATE_TEMP_OVERRIDE
    state.overrideIsOn      = false
    state.overrideStartTime = now()

    int expirySec = effectiveSec(TEMP_EXPIRY_SEC, testTempExpirySec)
    runIn(expirySec, "tempOverrideExpiryHandler")
    logInfo "TEMP_OVERRIDE entered (OFF). Expiry in ${expirySec}s. lastManualLevel cleared."

    turnDimmerOff()
}

/**
 * Double-press ON — enters LONG_OVERRIDE.
 *
 * Behavior:
 *   - Highest-priority override after MOVIE_MODE.
 *   - Exits Movie Mode if physical press occurred.
 *   - Lights forced ON; motion completely ignored for 8 hours.
 */
private void handleDoublePressOn() {
    logInfo "Double-press ON — from state ${state.currentState}. Entering LONG_OVERRIDE."

    if (state.currentState == STATE_MOVIE_MODE) {
        syncMovieSwitchOff()
    }

    cancelAllTimers()

    state.currentState      = STATE_LONG_OVERRIDE
    state.overrideIsOn      = true
    state.overrideStartTime = now()

    int dur = effectiveSec(LONG_OVERRIDE_SEC, testLongOverrideSec)
    runIn(dur, "longOverrideExpiryHandler")
    logInfo "LONG_OVERRIDE started. Ceiling in ${dur}s."

    int level = (state.lastManualLevel != null) ? (state.lastManualLevel as int) : defaultLevel()
    setDimmerLevel(level)
}

/**
 * Double-press OFF — enters MOTION_DISABLED. Wins over all other states (Q12).
 *
 * Behavior:
 *   - Cancels everything (LONG_OVERRIDE, MOVIE_MODE, TEMP_OVERRIDE).
 *   - Exits Movie Mode if physical press occurred.
 *   - Motion ignored for 4 hours; manual ON/OFF still work.
 *   - Turns lights OFF and clears lastManualLevel.
 */
private void handleDoublePressOff() {
    logInfo "Double-press OFF — from state ${state.currentState}. Entering MOTION_DISABLED."

    if (state.currentState == STATE_MOVIE_MODE) {
        syncMovieSwitchOff()
    }

    cancelAllTimers()

    state.currentState      = STATE_MOTION_DISABLED
    state.overrideIsOn      = false
    state.overrideStartTime = now()
    state.lastManualLevel   = null   // Explicit manual OFF clears level

    int dur = effectiveSec(MOTION_DISABLED_SEC, testMotionDisabledSec)
    runIn(dur, "motionDisabledExpiryHandler")
    logInfo "MOTION_DISABLED started. Ceiling in ${dur}s."

    turnDimmerOff()
}

// =============================================================================
// MOVIE MODE TRANSITIONS
// =============================================================================

/**
 * Enter MOVIE_MODE.
 *   - Cancels all current state timers (highest priority).
 *   - Turns lights OFF immediately.
 *   - Starts 4-hr ceiling timer.
 */
private void enterMovieMode() {
    logInfo "enterMovieMode() — from state ${state.currentState}."
    cancelAllTimers()

    state.currentState      = STATE_MOVIE_MODE
    state.overrideIsOn      = false
    state.overrideStartTime = now()

    turnDimmerOff()

    int dur = effectiveSec(MOVIE_MODE_SEC, testMovieModeSec)
    runIn(dur, "movieModeExpiryHandler")
    logInfo "MOVIE_MODE started. Ceiling in ${dur}s."
}

/**
 * Exit MOVIE_MODE → NORMAL.
 *   - Cancels Movie Mode timer.
 *   - Syncs Alexa Movie switch OFF if needed.
 *   - Transitions to NORMAL and re-evaluates motion.
 */
private void exitMovieMode() {
    logInfo "exitMovieMode() — transitioning to NORMAL."
    cancelAllTimers()
    syncMovieSwitchOff()

    state.currentState      = STATE_NORMAL
    state.overrideIsOn      = false
    state.overrideStartTime = null

    // Re-evaluate motion state now that motion is re-enabled.
    def motionValue = motionSensor?.currentValue("motion")
    if (motionValue == "active") {
        logInfo "exitMovieMode: motion currently active — turning lights on."
        turnOnToDefaultLevel()
    } else {
        logInfo "exitMovieMode: motion inactive — lights stay OFF, awaiting motion."
    }
}

// =============================================================================
// SCHEDULED TIMER HANDLERS
// =============================================================================

/**
 * Fires when the 5-min (NORMAL) motion off-timer expires.
 */
def motionTimeoutHandler() {
    logInfo "motionTimeoutHandler() — currentState=${state.currentState}"
    if (state.currentState != STATE_NORMAL) {
        logDebug "motionTimeoutHandler: state changed to ${state.currentState} — ignoring."
        return
    }
    // Motion timeout does NOT clear lastManualLevel (cleared only on explicit OFF)
    turnDimmerOff()
}

/**
 * Fires when the 30-min TEMP_OVERRIDE (ON) no-motion window expires.
 * Lights go OFF and state returns to NORMAL.
 */
def tempOverrideWindowHandler() {
    logInfo "tempOverrideWindowHandler() — 30-min no-motion window expired."
    if (state.currentState != STATE_TEMP_OVERRIDE || !state.overrideIsOn) {
        logDebug "tempOverrideWindowHandler: state is ${state.currentState}, overrideIsOn=${state.overrideIsOn} — ignoring."
        return
    }
    unschedule("tempOverrideExpiryHandler")

    state.currentState      = STATE_NORMAL
    state.overrideIsOn      = false
    state.overrideStartTime = null

    logInfo "TEMP_OVERRIDE no-motion window expired → NORMAL. Lights OFF."
    turnDimmerOff()
}

/**
 * Fires when the 2-hr absolute TEMP_OVERRIDE ceiling expires.
 * Returns to NORMAL and re-evaluates motion.
 */
def tempOverrideExpiryHandler() {
    logInfo "tempOverrideExpiryHandler() — 2-hr TEMP_OVERRIDE ceiling expired."
    if (state.currentState != STATE_TEMP_OVERRIDE) {
        logDebug "tempOverrideExpiryHandler: state is ${state.currentState} — ignoring."
        return
    }
    unschedule("tempOverrideWindowHandler")

    boolean wasOn = state.overrideIsOn
    state.currentState      = STATE_NORMAL
    state.overrideIsOn      = false
    state.overrideStartTime = null

    def motionValue = motionSensor?.currentValue("motion")
    if (motionValue == "active") {
        logInfo "tempOverrideExpiryHandler: motion active → NORMAL, lights ON at default."
        turnOnToDefaultLevel()
    } else {
        if (wasOn) {
            int delaySec = computeMotionDelaySec(
                               effectiveSec(MOTION_TIMEOUT_SEC, testMotionTimeoutSec))
            logInfo "tempOverrideExpiryHandler: motion inactive → NORMAL, off-timer in ${delaySec}s."
            runIn(delaySec, "motionTimeoutHandler")
        } else {
            logInfo "tempOverrideExpiryHandler: motion inactive & lights was OFF → NORMAL, awaiting motion."
        }
    }
}

/**
 * Fires when the 8-hr LONG_OVERRIDE ceiling expires.
 * Returns to NORMAL and re-evaluates motion.
 */
def longOverrideExpiryHandler() {
    logInfo "longOverrideExpiryHandler() — 8-hr LONG_OVERRIDE ceiling expired."
    if (state.currentState != STATE_LONG_OVERRIDE) {
        logDebug "longOverrideExpiryHandler: state is ${state.currentState} — ignoring."
        return
    }
    state.currentState      = STATE_NORMAL
    state.overrideIsOn      = false
    state.overrideStartTime = null

    def motionValue = motionSensor?.currentValue("motion")
    if (motionValue == "active") {
        logInfo "longOverrideExpiryHandler: motion active → NORMAL, lights stay ON at default."
        turnOnToDefaultLevel()
    } else {
        int delaySec = computeMotionDelaySec(
                           effectiveSec(MOTION_TIMEOUT_SEC, testMotionTimeoutSec))
        logInfo "longOverrideExpiryHandler: motion inactive → NORMAL, off-timer in ${delaySec}s."
        runIn(delaySec, "motionTimeoutHandler")
    }
}

/**
 * Fires when the 4-hr MOTION_DISABLED ceiling expires.
 * Returns to NORMAL and re-evaluates motion.
 */
def motionDisabledExpiryHandler() {
    logInfo "motionDisabledExpiryHandler() — 4-hr MOTION_DISABLED ceiling expired."
    if (state.currentState != STATE_MOTION_DISABLED) {
        logDebug "motionDisabledExpiryHandler: state is ${state.currentState} — ignoring."
        return
    }
    state.currentState      = STATE_NORMAL
    state.overrideIsOn      = false
    state.overrideStartTime = null

    def motionValue = motionSensor?.currentValue("motion")
    if (motionValue == "active") {
        logInfo "motionDisabledExpiryHandler: motion currently active → turning lights on."
        turnOnToDefaultLevel()
    } else {
        logInfo "motionDisabledExpiryHandler: motion inactive → NORMAL, awaiting motion."
    }
}

/**
 * Fires when the 4-hr MOVIE_MODE ceiling expires.
 */
def movieModeExpiryHandler() {
    logInfo "movieModeExpiryHandler() — 4-hr MOVIE_MODE ceiling expired."
    if (state.currentState != STATE_MOVIE_MODE) {
        logDebug "movieModeExpiryHandler: state is ${state.currentState} — ignoring."
        return
    }
    exitMovieMode()
}

/**
 * Auto-disables Debug logging after DEBUG_AUTO_OFF_SEC seconds.
 */
def disableDebugLogging() {
    if (logLevel == "Debug") {
        app.updateSetting("logLevel", [value: "Info", type: "enum"])
        log.info "[FamilyRoomLights] Debug logging auto-disabled after ${DEBUG_AUTO_OFF_SEC}s."
    }
}

// =============================================================================
// FLAG CLEAR HANDLERS
// =============================================================================

def clearAppSettingLevel() {
    atomicState.appSettingLevel = false
    logDebug "clearAppSettingLevel: flag cleared."
}

def clearAppTurningOff() {
    atomicState.appTurningOff = false
    logDebug "clearAppTurningOff: flag cleared."
}

def clearSyncingLightSwitch() {
    atomicState.syncingLightSwitch = false
    logDebug "clearSyncingLightSwitch: flag cleared."
}

def clearSyncingMovieSwitch() {
    atomicState.syncingMovieSwitch = false
    logDebug "clearSyncingMovieSwitch: flag cleared."
}

// =============================================================================
// LIGHT CONTROL HELPERS
// =============================================================================

/**
 * Turns the dimmer on at the contextually appropriate default level.
 * Daytime (mode or sunrise/sunset) → 100%.  After sunset → 20%.
 */
private void turnOnToDefaultLevel() {
    setDimmerLevel(defaultLevel())
}

/**
 * Determines the default level based on daytime definition (Q1/Q3).
 */
private int defaultLevel() {
    // Mode-based check
    if (daytimeModes) {
        String currentMode = location.mode
        if (daytimeModes.contains(currentMode)) {
            logDebug "defaultLevel: hub in daytime mode '${currentMode}' → ${LEVEL_DAYTIME}%"
            return LEVEL_DAYTIME
        } else {
            logDebug "defaultLevel: hub in mode '${currentMode}' (not daytime) → ${LEVEL_NIGHTTIME}%"
            return LEVEL_NIGHTTIME
        }
    }

    // Sunrise/sunset fallback
    def sun    = getSunriseAndSunset()
    Date nowDt = new Date()
    if (nowDt.after(sun.sunrise) && nowDt.before(sun.sunset)) {
        logDebug "defaultLevel: after sunrise, before sunset → ${LEVEL_DAYTIME}%"
        return LEVEL_DAYTIME
    } else {
        logDebug "defaultLevel: outside daylight hours → ${LEVEL_NIGHTTIME}%"
        return LEVEL_NIGHTTIME
    }
}

/**
 * Sets the dimmer to the given level and turns it on.
 * Commands are intentionally sent even when cached state already matches because some
 * Z-Wave dimmers do not reliably report physical state changes.
 */
private void setDimmerLevel(int level) {
    if (!dimmer) {
        logWarn "setDimmerLevel: dimmer device is null — cannot set level."
        return
    }
    logInfo "Setting dimmer to ${level}%."
    atomicState.appSettingLevel = true
    dimmer.setLevel(level)
    runIn(FLAG_CLEAR_DELAY_SEC, "clearAppSettingLevel")
    syncAlexaLightSwitchOn()
}

/**
 * Turns the dimmer off.
 * The command is always sent because cached device state may be stale.
 */
private void turnDimmerOff() {
    if (!dimmer) {
        logWarn "turnDimmerOff: dimmer device is null — cannot turn off."
        return
    }
    logInfo "Turning dimmer OFF."
    atomicState.appTurningOff = true
    dimmer.off()
    runIn(FLAG_CLEAR_DELAY_SEC, "clearAppTurningOff")
    syncAlexaLightSwitchOff()
}

/**
 * Syncs the Alexa Light virtual switch to ON with loop guard.
 */
private void syncAlexaLightSwitchOn() {
    if (!alexaLightSwitch) return
    if (alexaLightSwitch.currentValue("switch") == "on") return
    logDebug "syncAlexaLightSwitchOn: syncing alexaLightSwitch to ON."
    atomicState.syncingLightSwitch = true
    alexaLightSwitch.on()
    runIn(FLAG_CLEAR_DELAY_SEC, "clearSyncingLightSwitch")
}

/**
 * Syncs the Alexa Light virtual switch to OFF with loop guard.
 */
private void syncAlexaLightSwitchOff() {
    if (!alexaLightSwitch) return
    if (alexaLightSwitch.currentValue("switch") == "off") return
    logDebug "syncAlexaLightSwitchOff: syncing alexaLightSwitch to OFF."
    atomicState.syncingLightSwitch = true
    alexaLightSwitch.off()
    runIn(FLAG_CLEAR_DELAY_SEC, "clearSyncingLightSwitch")
}

/**
 * Writes OFF to the Movie Mode virtual switch with a feedback-loop guard.
 */
private void syncMovieSwitchOff() {
    if (!alexaMovieSwitch) return
    if (alexaMovieSwitch.currentValue("switch") == "off") return
    logDebug "syncMovieSwitchOff: writing OFF to alexaMovieSwitch."
    atomicState.syncingMovieSwitch = true
    alexaMovieSwitch.off()
    runIn(FLAG_CLEAR_DELAY_SEC, "clearSyncingMovieSwitch")
}

// =============================================================================
// LOGGING HELPERS
// =============================================================================

private void logDebug(String msg) {
    if (logLevel == "Debug") log.debug "[FamilyRoomLights] ${msg}"
}

private void logInfo(String msg) {
    if (logLevel == "Info" || logLevel == "Debug") log.info "[FamilyRoomLights] ${msg}"
}

private void logWarn(String msg) {
    log.warn "[FamilyRoomLights] ${msg}"
}
