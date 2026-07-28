package fr.black_eyes.lootchest.commands.commands;

import fr.black_eyes.lootchest.BuildInfo;
import fr.black_eyes.lootchest.Main;
import fr.black_eyes.lootchest.Messages;
import fr.black_eyes.lootchest.commands.SubCommand;
import org.bukkit.command.CommandSender;

/** Public introduction and canonical documentation link for the 1MoreBlock edition. */
public class InfoCommand extends SubCommand {

	public InfoCommand() {
		super("info");
	}

	@Override
	protected void onCommand(CommandSender sender, String[] args) {
		Main plugin = Main.getInstance();
		BuildInfo build = plugin.getBuildInfo();
		String version = plugin.getPluginMeta().getVersion();
		Messages.msg(sender, "info.title", "[Version]", version);
		Messages.msg(sender, "info.release",
				"[Build]", build.buildNumber(),
				"[Artifact]", build.artifactName());
		Messages.msg(sender, "info.source", "[Source]", build.sourceDisplay());
		Messages.msg(sender, "info.target",
				"[Paper]", build.paperTarget(),
				"[PaperApi]", build.paperApi(),
				"[Java]", build.javaTarget());
		Messages.msg(sender, "info.paper_release",
				"[Paper]", build.paperTarget(),
				"[PaperBuild]", build.paperBuild(),
				"[PaperChannel]", build.paperChannel());
		Messages.msg(sender, "info.holograms",
				"[Holograms]", plugin.getHologramIntegrationStatus());
		Messages.msg(sender, "info.introduction");
		Messages.msg(sender, "info.commands");
		Messages.msg(sender, "info.documentation");
	}
}
