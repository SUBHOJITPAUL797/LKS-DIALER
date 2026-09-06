/**
 * CallSounds.js
 * Professional telecom audio progress feedback using Web Audio API synthesis:
 * 1. Outgoing Ringback Tone: Telecom dual frequency (440Hz + 480Hz) supervisory tone (tuuuut... tuuuut...).
 * 2. Call Ended / Disconnect Tone: 3 crisp beeps when call drops or either party hangs up.
 * 3. Call Hold Tone: Pleasant double chime + gentle periodic reminder chime.
 * 4. Call Resume / Unhold Tone: Rising confirmation chime.
 */

class CallSoundsManager {
  constructor() {
    this.audioCtx = null;
    this.ringbackTimer = null;
    this.ringbackOsc1 = null;
    this.ringbackOsc2 = null;
    this.ringbackGain = null;
    this.isRingbackActive = false;
    this.holdReminderInterval = null;
  }

  getAudioContext() {
    if (!this.audioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) {
        this.audioCtx = new AudioContextClass();
      }
    }
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume().catch(() => {});
    }
    return this.audioCtx;
  }

  /**
   * Starts supervisory outgoing ringback tone: 440Hz + 480Hz dual tones.
   * Cadence: 1.8s tone ON, 2.5s silence OFF, repeated.
   */
  startRingbackTone() {
    if (this.isRingbackActive) return;
    this.isRingbackActive = true;
    const ctx = this.getAudioContext();
    if (!ctx) return;

    const playCadence = () => {
      if (!this.isRingbackActive) return;

      try {
        const now = ctx.currentTime;
        const gain = ctx.createGain();
        gain.gain.setValueAtTime(0.001, now);
        gain.gain.linearRampToValueAtTime(0.12, now + 0.05);
        gain.gain.setValueAtTime(0.12, now + 1.8);
        gain.gain.linearRampToValueAtTime(0.001, now + 1.85);

        const osc1 = ctx.createOscillator();
        const osc2 = ctx.createOscillator();

        osc1.type = 'sine';
        osc1.frequency.setValueAtTime(440, now); // US/Global telephony 440Hz

        osc2.type = 'sine';
        osc2.frequency.setValueAtTime(480, now); // US/Global telephony 480Hz

        osc1.connect(gain);
        osc2.connect(gain);
        gain.connect(ctx.destination);

        osc1.start(now);
        osc2.start(now);
        osc1.stop(now + 1.85);
        osc2.stop(now + 1.85);

        this.ringbackTimer = setTimeout(() => {
          if (this.isRingbackActive) playCadence();
        }, 4000); // 1.8s ON + 2.2s OFF
      } catch (e) {
        console.warn("Error playing ringback tone cadence:", e);
      }
    };

    playCadence();
  }

  /**
   * Stops the outgoing ringback tone immediately.
   */
  stopRingbackTone() {
    this.isRingbackActive = false;
    if (this.ringbackTimer) {
      clearTimeout(this.ringbackTimer);
      this.ringbackTimer = null;
    }
  }

  /**
   * Plays 3 distinct telecom disconnect / hangup beeps (480Hz).
   */
  playCallEndedTone() {
    this.stopRingbackTone();
    this.stopHoldReminder();

    const ctx = this.getAudioContext();
    if (!ctx) return;

    try {
      const beeps = [
        { start: 0.0, dur: 0.18, freq: 480 },
        { start: 0.26, dur: 0.18, freq: 480 },
        { start: 0.52, dur: 0.28, freq: 480 },
      ];

      const now = ctx.currentTime;
      beeps.forEach(({ start, dur, freq }) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();

        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + start);

        gain.gain.setValueAtTime(0.001, now + start);
        gain.gain.linearRampToValueAtTime(0.15, now + start + 0.02);
        gain.gain.setValueAtTime(0.15, now + start + dur - 0.02);
        gain.gain.linearRampToValueAtTime(0.001, now + start + dur);

        osc.connect(gain);
        gain.connect(ctx.destination);

        osc.start(now + start);
        osc.stop(now + start + dur);
      });
    } catch (e) {
      console.warn("Error playing call ended tone:", e);
    }
  }

  /**
   * Plays double chime on hold + starts 8s periodic reminder beep.
   */
  playHoldTone() {
    this.stopRingbackTone();
    this.stopHoldReminder();

    const ctx = this.getAudioContext();
    if (!ctx) return;

    try {
      // Pleasant double chime: 587Hz (D5) -> 880Hz (A5)
      const now = ctx.currentTime;
      const notes = [
        { start: 0.0, dur: 0.2, freq: 587 },
        { start: 0.22, dur: 0.35, freq: 880 }
      ];

      notes.forEach(({ start, dur, freq }) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + start);

        gain.gain.setValueAtTime(0.001, now + start);
        gain.gain.linearRampToValueAtTime(0.15, now + start + 0.03);
        gain.gain.exponentialRampToValueAtTime(0.001, now + start + dur);

        osc.connect(gain);
        gain.connect(ctx.destination);

        osc.start(now + start);
        osc.stop(now + start + dur);
      });
    } catch (e) {
      console.warn("Error playing hold tone:", e);
    }

    // Periodic reminder tone every 8s
    this.holdReminderInterval = setInterval(() => {
      try {
        const c = this.getAudioContext();
        if (!c) return;
        const t = c.currentTime;
        const osc = c.createOscillator();
        const gain = c.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(600, t);
        gain.gain.setValueAtTime(0.001, t);
        gain.gain.linearRampToValueAtTime(0.08, t + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.001, t + 0.15);
        osc.connect(gain);
        gain.connect(c.destination);
        osc.start(t);
        osc.stop(t + 0.16);
      } catch (_) {}
    }, 8000);
  }

  /**
   * Plays pleasant rising chime on unhold / resume.
   */
  playUnholdTone() {
    this.stopHoldReminder();

    const ctx = this.getAudioContext();
    if (!ctx) return;

    try {
      // Rising chime: 440Hz (A4) -> 659Hz (E5)
      const now = ctx.currentTime;
      const notes = [
        { start: 0.0, dur: 0.18, freq: 440 },
        { start: 0.15, dur: 0.35, freq: 659 }
      ];

      notes.forEach(({ start, dur, freq }) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + start);

        gain.gain.setValueAtTime(0.001, now + start);
        gain.gain.linearRampToValueAtTime(0.15, now + start + 0.03);
        gain.gain.exponentialRampToValueAtTime(0.001, now + start + dur);

        osc.connect(gain);
        gain.connect(ctx.destination);

        osc.start(now + start);
        osc.stop(now + start + dur);
      });
    } catch (e) {
      console.warn("Error playing unhold tone:", e);
    }
  }

  stopHoldReminder() {
    if (this.holdReminderInterval) {
      clearInterval(this.holdReminderInterval);
      this.holdReminderInterval = null;
    }
  }

  resetAll() {
    this.stopRingbackTone();
    this.stopHoldReminder();
  }
}

export const callSounds = new CallSoundsManager();
