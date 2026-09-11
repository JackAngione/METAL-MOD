# Project
This is a Minecraft mod which the entire purpose is to maximally optimize Minecraft for MacOS/Apple Silicon. Always use the Metal Graphics API for all graphics rendering and features.  


# Agent instructions
- Use a standard world to test, NOT a flat world.
- When following plans, mark off the tasks as you complete them.



## Testing:
- Always use 16 render and simulation distance. Default graphics engine so that metal is used. 

When implementing, reviewing, or validating water shader effects, read
[docs/WATER_EFFECTS_PLAN.md](docs/WATER_EFFECTS_PLAN.md) first. Follow its progress protocol:
claim the relevant task, update partial progress or blockers, and mark its checkbox complete
with validation evidence when its acceptance criteria pass. Include the plan update in the
same change as the water implementation.
