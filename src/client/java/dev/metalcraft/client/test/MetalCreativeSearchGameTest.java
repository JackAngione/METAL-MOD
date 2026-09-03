package dev.metalcraft.client.test;

import com.mojang.logging.LogUtils;
import dev.metalcraft.client.mixin.CreativeModeInventoryScreenInvoker;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.item.CreativeModeTabs;
import org.slf4j.Logger;

/**
 * Validation of the creative search tab, which is where the GUI item atlas recycles slots.
 *
 * <p>Typing a query repopulates the result grid every keystroke, so the atlas clears and redraws
 * individual slots. That regional attachment clear has no direct Metal equivalent and used to throw
 * on the first keystroke, taking the client down.
 */
final class MetalCreativeSearchGameTest {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String QUERY = "stone";

	private final ClientGameTestContext context;

	MetalCreativeSearchGameTest(final ClientGameTestContext context) {
		this.context = context;
	}

	void run() {
		var worldBuilder = this.context.worldBuilder()
			.adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE));

		try (TestSingleplayerContext _ = worldBuilder.create()) {
			this.context.waitFor(client -> client.level != null && client.player != null);
			this.context.waitTicks(20);
			LOGGER.info("Metal creative search validation: world loaded");

			this.context.runOnClient(client -> client.gui.setScreen(
				new CreativeModeInventoryScreen(client.player, client.player.connection.enabledFeatures(), true)));
			this.context.waitTicks(10);
			LOGGER.info("Metal creative search validation: creative screen open");

			this.context.runOnClient(client -> {
				if (client.gui.screen() instanceof CreativeModeInventoryScreenInvoker invoker) {
					invoker.metalcraft$selectTab(CreativeModeTabs.searchTab());
				} else {
					throw new AssertionError("Creative screen did not open: " + client.gui.screen());
				}
			});
			this.context.waitTicks(10);
			LOGGER.info("Metal creative search validation: search tab selected, rendered clean");

			for (int index = 0; index < QUERY.length(); index++) {
				char typed = QUERY.charAt(index);
				this.context.getInput().typeChar(typed);
				this.context.waitTicks(5);
				LOGGER.info("Metal creative search validation: rendered results for \"{}\"", QUERY.substring(0, index + 1));
			}

			this.context.waitTicks(20);
			this.context.takeScreenshot("metalcraft-creative-search");
			LOGGER.info("Metal creative search validation: item atlas survived a full query");
		}
	}
}
