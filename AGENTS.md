# Project
This is a Minecraft mod which the entire purpose is to maximally optimize Minecraft for MacOS/Apple Silicon. Always use the Metal Graphics API for all graphics rendering and features.  


# Agent instructions
- Do not create or update documentation unless the user explicitly asks for it. This includes README files, guides, reports, evidence write-ups, roadmaps, and plans. Keep routine explanations and validation results in chat.
- Use a standard world to test, NOT a flat world.
- Never commit screenshots. Keep captures local and ignored by Git; do not force-add them as validation evidence.
- Commit image files used as actual game assets (textures, sprites, UI art, etc.). Scope screenshot ignore rules to capture locations; never ignore image extensions globally or exclude game resource directories.
- When following plans, mark off the tasks as you complete them.



## Testing:
- Always run in-game visual and performance tests at 4K (3840×2160), unless the user explicitly requests another resolution. Verify the actual world render-target dimensions rather than relying only on the requested window size; record the drawable/presentation dimensions separately.
- Use 16 render and simulation distance for shaders testing. When doing LOD testing, use 128 render distance. Default graphics engine so that metal is used. 
- Keep live in-game test runs to a minimum. Do not run unnecessarily long test. It wastes time. Only increase in-game test time/complexity when absolutely necessary.
- Reuse an existing standard-world save for test runs when practical. Do not create a new world unless the test explicitly needs a fresh world or different world configuration.
