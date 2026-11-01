package dev.k3ytel.hearnodub.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.k3ytel.hearnodub.client.gui.HearNoDubConfigScreen;

/** Adds the "Config" button for Hear No Dub in Mod Menu's mod list. Only loaded if Mod Menu is installed. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return HearNoDubConfigScreen::create;
	}
}
