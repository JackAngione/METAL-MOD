# Project
This is a Minecraft mod which the entire purpose is to maximally optimize Minecraft for MacOS/Apple Silicon. Always use the Metal Graphics API for all graphics rendering and features.  


# Agent instructions
- Use a standard world to test, NOT a flat world.
- Never commit screenshots. Keep captures local and ignored by Git; do not force-add them as validation evidence.
- Commit image files used as actual game assets (textures, sprites, UI art, etc.). Scope screenshot ignore rules to capture locations; never ignore image extensions globally or exclude game resource directories.
- When following plans, mark off the tasks as you complete them.



## Testing:
- Use 16 render and simulation distance for shaders testing. When doing LOD testing, use 128 render distance. Default graphics engine so that metal is used. 
- Keep live in-game test runs to a minimum. Do not run unnecessarily long test. It wastes time. Only increase in-game test time/complexity when absolutely necessary.

When implementing, reviewing, or validating water shader effects, read
[docs/WATER_EFFECTS_PLAN.md](docs/WATER_EFFECTS_PLAN.md) first. Follow its progress protocol:
claim the relevant task, update partial progress or blockers, and mark its checkbox complete
with validation evidence when its acceptance criteria pass. Include the plan update in the
same change as the water implementation.
