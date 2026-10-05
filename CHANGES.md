# Integrated fixes

Base: GitHub `a701f00` plus the recovered feature-bearing diffs from the previous complete work copy.

- Playback speed persisted at x1.0, x1.5, or x2.0 and reapplied to dynamically replaced video elements.
- Configured video-control hosts and fullscreen video controls retained.
- PiP/browser coexistence task flow retained without PiP custom white actions.
- Proportional right-edge scroll picker retained.
- Google image details use direct native WebView touch and scrolling.
- Normal and high ad filtering use the common Brave path; only media and playback subdocuments are protected while script/XHR are evaluated.
- IME, private-tab styling, home/history, dark-mode exclusions, and download UI retained.
- AdGuard Android filter 101 and all references to it removed.
