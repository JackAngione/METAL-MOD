# Interrupted disabled comparison

The native Standard/full pre-LOD case exhausts three attempts, each rejected by continuous foreground checks (`AppKit state=14, focused=0`). These are not performance samples. A subsequent read-only NSWorkspace foreground check reports Google Chrome active; the display is unlocked but Minecraft is not foreground. The two previously completed native None/full and None/half pairs remain qualified and pass both frame-interval limits.

The user was asked to leave Minecraft foreground for the remaining captures. The runner preserves old attempts before any resumed retry set. No source changes occur during accepted timed captures.

Subsequent disposition (2026-09-12): the user directed us to skip the remaining long tests. Foreground availability is no longer a P8 completion requirement. These failed attempts remain rejected evidence.
