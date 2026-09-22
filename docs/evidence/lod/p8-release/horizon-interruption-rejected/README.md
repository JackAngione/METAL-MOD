# Rejected focus interruption, 2026-09-12 evening

The 5m41s attempt reaches the 128-chunk matrix, then a phase loses foreground focus (`AppKit state=14, focused=0`). The client exits nonzero and the attempt is rejected. Unlike the earlier lock, the window remains visible but another application becomes active.

The follow-up harness discards an interrupted phase completely, records its rejected frame count/reason, reacquires focus and waits 20 ticks before a new 30-frame warm-up and 80-tick capture. It allows at most three attempts. Mode/drawable changes and GPU/resource failures remain fatal; no failing timing result is reused.
