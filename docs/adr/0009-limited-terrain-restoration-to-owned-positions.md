# Limited temporary terrain restoration to owned positions

Temporary terrain effects restore only positions they still own, revealing the remaining active effect layer or the original block. Later external or permanent edits take precedence. Normal world physics continues, and indirect fluid spread, fire, falling blocks, and redstone consequences are not globally reversed.

We chose restoration of tracked positions to avoid a general world rollback system, accepting that temporary terrain can cause lasting indirect changes. Saved restoration records allow required cleanup to finish across chunk unloads and world restarts without keeping those chunks loaded.
