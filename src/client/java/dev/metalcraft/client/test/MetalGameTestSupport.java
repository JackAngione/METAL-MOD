package dev.metalcraft.client.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.server.MinecraftServer;
import org.lwjgl.glfw.GLFW;

final class MetalGameTestSupport {
    private MetalGameTestSupport() { }

    static void server(ClientGameTestContext context, Consumer<MinecraftServer> action) {
        CompletableFuture<?> done = context.computeOnClient(client -> {
            var server = client.getSingleplayerServer();
            return server.submit(() -> action.accept(server));
        });
        context.waitFor(client -> done.isDone());
        done.join();
    }

    static void command(ClientGameTestContext context, String command) {
        server(context, server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    static List<AbstractWidget> widgets(ContainerEventHandler parent) {
        var found = new ArrayList<AbstractWidget>();
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget widget) found.add(widget);
            if (child instanceof ContainerEventHandler container) found.addAll(widgets(container));
        }
        return found;
    }

    static void press(ClientGameTestContext context, String label) {
        context.runOnClient(client -> {
            Button button = (Button)widgets(client.gui.screen()).stream().filter(widget -> widget instanceof Button
                && widget.getMessage().getString().startsWith(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Button not found: " + label));
            if (!button.active) throw new AssertionError("Button is enabled: " + label);
            button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        });
    }

    static void copySaveFiles(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (path.getFileName().toString().equals("session.lock")) continue;
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }
}
