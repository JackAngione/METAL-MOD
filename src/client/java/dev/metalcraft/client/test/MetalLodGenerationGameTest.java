package dev.metalcraft.client.test;

import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodDistantNode;
import dev.metalcraft.client.lod.LodDistantRenderer;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.lod.LodTerrainGeneration;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Stationary NORMAL-world proof: generated terrain is cached and uploaded without client chunk receipt. */
final class MetalLodGenerationGameTest {
    static void run(ClientGameTestContext context) {
        var saved=MetalCraftConfig.lod();
        String pack=context.computeOnClient(c->ShaderPackRuntime.active().selectedPackId());
        var report=new LinkedHashMap<String,Object>();
        var builder=context.worldBuilder().adjustSettings(settings->{
            var normal=settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
            settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
            settings.setSeed("metalcraft"); settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        });
        try {
            context.runOnClient(c->{ MetalCraftConfig.setLod(LodSettings.defaults()); ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID); });
            try(var world=builder.create()) {
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a -1535.5 236 -127.5 135 15");
                world.getServer().runCommand("time set noon"); world.getServer().runCommand("weather clear");
                context.getInput().lookAt(135,15);
                context.waitTicks(80);
                int x=-113,z=-25;
                context.runOnClient(c->check(!c.level.getChunkSource().hasChunk(x,z),"target not received before generation"));
                context.runOnClient(c->MetalCraftConfig.setLod(LodSettings.defaults().withEnabled(true).withHorizon(32,true,512)));
                context.waitFor(c->LodTerrainGeneration.stats().active(),2400);
                context.waitFor(c->LodTerrainGeneration.stats().tickets()==9,400);
                world.getServer().runOnServer(server->check(!server.overworld().getChunkSource()
                        .isPositionTicking(net.minecraft.world.level.ChunkPos.pack(x,z)),"active generation does not simulate the target"));
                report.put("beforePausedStop",LodTerrainGeneration.stats());
                context.runOnClient(c->{
                    check(LodTerrainGeneration.stats().tickets()==9,"pause test interrupts active generation tickets");
                    c.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                });
                context.waitFor(c->c.getSingleplayerServer().isPaused(),200);
                context.runOnClient(c->MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeneration(false)));
                context.waitFor(c->!LodTerrainGeneration.stats().active() && LodTerrainGeneration.stats().tickets()==0,200);
                report.put("pausedStop",LodTerrainGeneration.stats());
                context.runOnClient(c->{ c.gui.setScreen(null); MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeneration(true)); });
                context.waitFor(c->{
                    var s=LodTerrainGeneration.stats();
                    check(s.tickets()<=9 && s.failures()==0,"bounded generation without failures: "+s);
                    check(!c.level.getChunkSource().hasChunk(x,z),"generation must not inject chunks into the client");
                    return s.columns()>=1;
                },2400);
                report.put("generation",LodTerrainGeneration.stats());
                context.runOnClient(c->MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeneration(false)));
                context.waitFor(c->!LodTerrainGeneration.stats().active() && LodTerrainGeneration.stats().tickets()==0,200);
                report.put("stopped",LodTerrainGeneration.stats());
                var marker=world.getServer().computeOnServer(server->{
                    var level=server.overworld();
                    check(!level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.pack(x,z)),"generated terrain is not simulated");
                    // Column sections are already persisted; choose any drawable cached surface below the camera.
                    return new LodDistantNode.Key(0,x,5,z);
                });
                context.waitFor(c->LodDistantRenderer.entry(marker).stored(),400);
                context.waitFor(c->{
                    for(int y=c.level.getMinSectionY();y<c.level.getMaxSectionY();y++)
                        if(LodDistantRenderer.isResident(new LodDistantNode.Key(0,x,y,z))) return LodDistantRenderer.stats().frameDraws()>0;
                    return false;
                },400);
                context.waitTicks(5);
                context.runOnClient(c->{
                    check(!c.level.getChunkSource().hasChunk(x,z),"generated LOD is visible without receiving the target chunk");
                    check(c.options.renderDistance().get()==16 && c.options.simulationDistance().get()==16,"distances remain 16/16");
                    check(c.player.getX()==-1535.5 && c.player.getZ()==-127.5,"player stayed stationary");
                    c.gui.hud.getChat().clearMessages(true);
                });
                report.put("distant",LodDistantRenderer.stats());
                context.takeScreenshot("metalcraft-lod-generated");
                context.runOnClient(c->MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(false)));
                context.waitFor(c->LodDistantRenderer.stats().gpuBytes()==0,200);
                report.put("disabled",LodDistantRenderer.stats());
            }
            context.waitFor(c->LodTerrainGeneration.stats().tickets()==0,200);
            System.out.println("LOD generation passed: stationary unreceived column cached/rendered, 16/16, no simulation, stop/disable/close");
        } finally {
            context.runOnClient(c->{ MetalCraftConfig.setLod(saved); ShaderPackRuntime.active().selectPack(pack); });
            try { Files.writeString(Path.of("build/lod-generation.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch(java.io.IOException error) { throw new AssertionError(error); }
        }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
