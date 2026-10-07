# METAL MOD

METAL MOD is an in-development, client-side Fabric mod that gives Minecraft Java Edition a native Apple Metal rendering backend for running on Apple Silicon.

## GOAL
The purpose of this mod is to make Minecraft perform as optimal as it can on MacOS/Apple Silicon.
Desired client-side mod features are implemented natively with Metal.

## FEATURES
- General backend performance optimizations
- Proprietary Shaders Implementation
  - Color Grading Settings including Film Grain, Bloom, and Depth-of-field
- \*In Progress\* Level-Of-Detail feature allowing for render distances up to 1024 chunks at significantlreduced performance cost
  
(All testing is done on an M4 Max Mac Studio)

## RUN / BUILD THE MOD
Run the Dev Game: `./gradlew runClient` (from the project directory with JDK 25)  
Build the mod JAR: `./gradlew build` the distributable JAR is in `build/libs/`
