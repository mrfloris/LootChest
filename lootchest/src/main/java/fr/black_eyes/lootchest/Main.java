package fr.black_eyes.lootchest;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

import com.Zrips.CMI.CMI;
import fr.black_eyes.lootchest.commands.SubCommand;
import fr.black_eyes.lootchest.compat.CompatibilityMigrations;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import fr.black_eyes.lootchest.commands.CommandHandler;
import fr.black_eyes.lootchest.listeners.DeleteListener;
import fr.black_eyes.lootchest.listeners.UiListener;
import fr.black_eyes.lootchest.lifecycle.ChestLifecycle;
import fr.black_eyes.lootchest.index.BlockLocationIndex;
import fr.black_eyes.lootchest.particles.ParticleCatalog;
import fr.black_eyes.lootchest.scheduler.TaskRegistry;
import fr.black_eyes.lootchest.ui.ChestUi;
import fr.black_eyes.lootchest.ui.UiHandler;
import lombok.Getter;
import lombok.Setter;

import static fr.black_eyes.lootchest.Constants.DATA_CHEST_PATH;


public class Main extends JavaPlugin {
	public static final String MENU_MAIN_TYPE = "Menu.main.type";
	private static final String PARTICLE_TASK = "particles";
	private static final String STARTUP_TASK = "startup";
	private static final String CHEST_LOAD_TASK = "chest-load";
	private static final String CHEST_SPAWN_TASK = "chest-spawn";
	private static final String CHEST_BULK_TASK = "chest-bulk";
	@Getter private final HashMap<Location, Long> protection = new HashMap<>();
	@Getter private final LinkedHashMap<String, Particle> particles = new LinkedHashMap<>();
	@Getter private final HashMap<Location, Particle> part = new HashMap<>();
	@Getter private ParticleCatalog particleCatalog;
	private final Set<Particle> failedParticles = EnumSet.noneOf(Particle.class);
	@Setter public static Config configs;
	@Getter private HashMap<String, Lootchest> lootChest;
	@Getter private BlockLocationIndex<Lootchest> lootChestLocationIndex;
	@Getter @Setter private static Main instance;
	@Getter private LootChestUtils utils;
	@Getter private boolean cmiHologramsAvailable;
	@Getter private UiHandler uiHandler;
	@Getter private TaskRegistry taskRegistry;
	@Getter private LootChestFiles configFiles;
	private DeleteListener deleteListener;
	@Getter private BuildInfo buildInfo = BuildInfo.unknown();
	@Getter private String hologramIntegrationStatus = "disabled during startup";
	@Getter private boolean chestWorkInProgress;
	private boolean cmiVersionWarningLogged;
	private final Set<String> locationIndexMismatchWarnings = new HashSet<>();


	@Override
	public void onDisable() {
		if (uiHandler != null) {
			uiHandler.closeAll(ChestUi.CloseReason.SHUTDOWN);
		}
		if (deleteListener != null) {
			deleteListener.clearTrackedInventories();
		}
		if (taskRegistry != null) {
			taskRegistry.cancelAll();
		}
		if (lootChest != null) {
			lootChest.values().forEach(chest -> chest.getHologram().remove());
		}
		if (lootChest != null && configFiles != null && configFiles.isInitialized()) {
			try {
				LootChestUtils.saveAllChests();
				configFiles.flush();
				configFiles.backupData();
				Messages.log("<#a6e3a1>Backed up data file for rollback.");
			} catch (IOException | RuntimeException e) {
				getLogger().log(Level.SEVERE, "Could not finish saving LootChest data", e);
			}
		}
		if (configFiles != null) {
			configFiles.close();
		}
		if (lootChestLocationIndex != null) {
			lootChestLocationIndex.clear();
			locationIndexMismatchWarnings.clear();
		}
	}

	@Override
	public void onEnable() {
		setInstance(this);
		buildInfo = BuildInfo.load(getLogger());

		getLogger().info("Loading config files...");
		configFiles = new LootChestFiles(this);
		try {
			configFiles.initialize();
		} catch (IOException | InvalidConfigurationException | RuntimeException e) {
			getLogger().log(
					Level.SEVERE,
					"Configuration or data files could not be initialized. LootChest will stop.",
					e);
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		lootChest = new HashMap<>();
		lootChestLocationIndex = new BlockLocationIndex<>();
		taskRegistry = new TaskRegistry(this);

		Messages.log("config files loaded");
		Messages.log("Server version: " + Bukkit.getMinecraftVersion());
		Messages.log(
				"<#a6e3a1>Release: <#89dceb>[Artifact] <#6c7086>| <#a6e3a1>build <#89dceb>[Build] "
						+ "<#6c7086>| <#a6e3a1>source <#89dceb>[Source] <#6c7086>| "
						+ "<#a6e3a1>Paper <#89dceb>[Paper] build [PaperBuild] [PaperChannel] "
						+ "<#6c7086>(API [PaperApi]) | "
						+ "<#a6e3a1>Java <#89dceb>[Java]",
				"[Artifact]", buildInfo.artifactName(),
				"[Build]", buildInfo.buildNumber(),
				"[Source]", buildInfo.sourceDisplay(),
				"[Paper]", buildInfo.paperTarget(),
				"[PaperBuild]", buildInfo.paperBuild(),
				"[PaperChannel]", buildInfo.paperChannel(),
				"[PaperApi]", buildInfo.paperApi(),
				"[Java]", buildInfo.javaTarget());
		// Add newly introduced defaults without removing local settings.
		updateOldConfig();
		configFiles.reloadConfig();
		utils = new LootChestUtils();

		uiHandler = new UiHandler(this);
		registerEvents(uiHandler);
		registerCommands();
		
		//load config
		setConfigs(Config.getInstance(configFiles.getConfig()));
		startCmiHolograms();
 
	        Messages.log("Starting particles...");
        
		reloadParticleCatalog();
        if(configs.partEnable) {
			//loop de tous les coffres tous les 1/4 (modifiable dans la config) de secondes pour faire spawn des particules
			//loop of all chests every 1/4 (editable in config) of seconds to spawn particles 
			startParticles();
		}
    	
    	//Loads all chests asynchronously
    	loadChests();
        
	}

	private void startCmiHolograms() {
		cmiHologramsAvailable = false;
		if (!configs.usehologram) {
			hologramIntegrationStatus = "disabled by configuration";
			return;
		}

		PluginManager pluginManager = Bukkit.getPluginManager();
		Plugin cmiPlugin = pluginManager.getPlugin("CMI");
		Plugin cmiLibPlugin = pluginManager.getPlugin("CMILib");
		if (cmiPlugin == null || cmiLibPlugin == null
				|| !cmiPlugin.isEnabled() || !cmiLibPlugin.isEnabled()) {
			configs.usehologram = false;
			hologramIntegrationStatus = "disabled (optional CMI/CMILib unavailable)";
			getLogger().warning("CMI and CMILib are not both enabled; LootChest will continue without holograms.");
			return;
		}

		try {
			CMI cmi = CMI.getInstance();
			if (cmi == null || cmi.getHologramManager() == null) {
				configs.usehologram = false;
				hologramIntegrationStatus = "disabled (CMI hologram manager unavailable)";
				getLogger().warning("CMI's hologram manager is unavailable; LootChest holograms are disabled.");
				return;
			}
			String cmiVersion = cmiPlugin.getPluginMeta().getVersion();
			String cmiLibVersion = cmiLibPlugin.getPluginMeta().getVersion();
			cmiHologramsAvailable = true;
			hologramIntegrationStatus = "CMI " + cmiVersion + " / CMILib " + cmiLibVersion;
			Messages.log(
					"<#a6e3a1>Using CMI holograms: <#89dceb>CMI [Cmi] <#6c7086>/ <#89dceb>CMILib [CmiLib]",
					"[Cmi]", cmiVersion,
					"[CmiLib]", cmiLibVersion);
			if (!cmiVersionWarningLogged
					&& !"unknown".equals(buildInfo.cmiTestedVersion())
					&& (!buildInfo.cmiTestedVersion().equals(cmiVersion)
					|| !buildInfo.cmiLibTestedVersion().equals(cmiLibVersion))) {
				cmiVersionWarningLogged = true;
				getLogger().warning("CMI holograms are running with an unvalidated version pair. "
						+ "This build is supported with CMI " + buildInfo.cmiTestedVersion()
						+ " and CMILib " + buildInfo.cmiLibTestedVersion() + ".");
			}
		} catch (RuntimeException | LinkageError e) {
			configs.usehologram = false;
			cmiHologramsAvailable = false;
			hologramIntegrationStatus = "disabled (CMI integration error)";
			getLogger().warning("CMI holograms failed to start; LootChest holograms are disabled. "
					+ e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	private void registerEvents(UiHandler uiHandler) {
		PluginManager pluginManager = Bukkit.getPluginManager();
		deleteListener = new DeleteListener();
		pluginManager.registerEvents(deleteListener, this);
		pluginManager.registerEvents(new UiListener(uiHandler), this);
	}

	private void registerCommands() {
		CommandHandler cmdHandler = new CommandHandler(this, "lootchest");
		String commandsPackage = "fr/black_eyes/lootchest/commands/commands/";
		// get all class names in the commands package instead of hardcoding them
		for (String command : LootChestUtils.getClassesFromJARFile("fr/black_eyes/lootchest/commands/commands/")) {
            try {
                cmdHandler.addSubCommand((SubCommand) Class.forName(commandsPackage.replace("/", ".") + command).getConstructor().newInstance());
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException | ClassNotFoundException e) {
				Messages.log("<#f38ba8>Error while registering command " + command);
            }
        }

	}
	
	/**
	 * Loop all chests every 1/4 of second (configurable in config.yml) and spawns particles around it.
	 * Servers with bad performances (or with 400 chests) should disable particles.
	 */
	private void startParticles() {
		taskRegistry.runRepeating(PARTICLE_TASK, () -> {
				if (!configs.partEnable) {
					return;
				}
				for (Map.Entry<Location, Particle> entry : part.entrySet()) {
					Location location = entry.getKey();
					Particle particle = entry.getValue();
					if (particle == null || location.getWorld() == null
							|| !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
						continue;
					}
					try {
						particleCatalog.display(particle, location, configs.partNumber,
								configs.PART_radius, configs.PART_speed, location.getWorld().getPlayers());
					} catch (RuntimeException exception) {
						if (failedParticles.add(particle)) {
							getLogger().warning("Particle " + particle.name() + " failed to spawn; using "
									+ particleCatalog.getFallback().name() + ". "
									+ exception.getClass().getSimpleName() + ": " + exception.getMessage());
						}
						entry.setValue(particleCatalog.getFallback());
					}
				}
			}, 0L, configs.partRespawnTicks);
	}
    		
	/**
	 * Loads all chests after the configured startup delay.
	 */
	private void loadChests() {
		long countdown = configs.cooldownBeforePluginStart;
    	if(countdown>0) 
			Messages.log("Chests will load in "+ countdown + " seconds.");

		// Preserve the established startup timing while moving task ownership into the registry.
		taskRegistry.runLater(STARTUP_TASK, () -> {
			Messages.log("Loading chests...");
			loadChestDefinitions(false, () -> Messages.log("Plugin loaded"));
		}, countdown + 20L);
	}

	public void reloadLootChests(Runnable completion) {
		uiHandler.closeAll(ChestUi.CloseReason.RELOAD);
		deleteListener.clearTrackedInventories();
		taskRegistry.cancelAll();
		chestWorkInProgress = false;

		if (configs.saveDataFileDuringReload) {
			LootChestUtils.saveAllChests();
		} else {
			configFiles.reloadData();
		}
		lootChestLocationIndex.clear();
		locationIndexMismatchWarnings.clear();
		ChestLifecycle.clearForReload(
				lootChest,
				chest -> chest.getHologram().remove(),
				part,
				protection);

		configFiles.reloadConfig();
		setConfigs(Config.getInstance(configFiles.getConfig()));
		startCmiHolograms();
		reloadParticleCatalog();
		if (configs.partEnable) {
			startParticles();
		}

		Messages.log("Loading chests...");
		loadChestDefinitions(true, completion);
	}

	public boolean runBatchedChestOperation(
			Collection<Lootchest> chests,
			Consumer<Lootchest> operation,
			Runnable completion
	) {
		if (chestWorkInProgress || taskRegistry.hasTask(CHEST_BULK_TASK)) {
			return false;
		}
		chestWorkInProgress = true;
		taskRegistry.runBatched(
				CHEST_BULK_TASK,
				new ArrayList<>(chests),
				configs.chestsPerTick,
				operation,
				() -> {
					chestWorkInProgress = false;
					completion.run();
				});
		return true;
	}

	private void loadChestDefinitions(boolean forceSpawn, Runnable completion) {
		chestWorkInProgress = true;
		long startedAt = System.currentTimeMillis();
		ConfigurationSection chestSection = configFiles.getData().getConfigurationSection("chests");
		List<String> chestNames = chestSection == null
				? Collections.emptyList()
				: new ArrayList<>(chestSection.getKeys(false));

		taskRegistry.runBatched(
				CHEST_LOAD_TASK,
				chestNames,
				configs.chestsPerTick,
				this::loadChestDefinition,
				() -> {
					rebuildLootChestLocationIndex();
					Messages.log("Loaded " + lootChest.size() + " Lootchests in "
							+ (System.currentTimeMillis() - startedAt) + " milliseconds.");
					Messages.log("Starting LootChest timers in batches...");
					taskRegistry.runBatched(
							CHEST_SPAWN_TASK,
							new ArrayList<>(lootChest.values()),
							configs.chestsPerTick,
							chest -> spawnLoadedChest(chest, forceSpawn),
							() -> {
								chestWorkInProgress = false;
								completion.run();
							});
				});
	}

	private void loadChestDefinition(String chestName) {
		String worldName = configFiles.getData().getString(DATA_CHEST_PATH + chestName + ".position.world");
		String randomWorldName = worldName;
		if (configFiles.getData().getInt(DATA_CHEST_PATH + chestName + ".randomradius") > 0) {
			randomWorldName = configFiles.getData().getString(DATA_CHEST_PATH + chestName + ".randomPosition.world");
		}
		if (worldName != null
				&& LootChestUtils.isWorldLoaded(randomWorldName)
				&& LootChestUtils.isWorldLoaded(worldName)) {
			Lootchest chest = new Lootchest(chestName);
			lootChest.put(chestName, chest);
			trackLootChestLocation(chest);
			return;
		}
		Messages.log("<#f38ba8>Could not load LootChest " + chestName + ": world " + worldName + " is not loaded.");
	}

	private void rebuildLootChestLocationIndex() {
		lootChestLocationIndex.clear();
		locationIndexMismatchWarnings.clear();
		lootChest.values().forEach(this::trackLootChestLocation);
		if (configs != null && configs.debug) {
			Messages.log(
					"<#89b4fa>Guarded location index tracks [Indexed] of [Loaded] loaded LootChests; "
							+ "the exact-location scan remains the fallback and debug verifier.",
					"[Indexed]", Integer.toString(lootChestLocationIndex.size()),
					"[Loaded]", Integer.toString(lootChest.size()));
		}
	}

	public void trackLootChestLocation(Lootchest chest) {
		if (chest == null || lootChestLocationIndex == null
				|| lootChest == null || !lootChest.containsValue(chest)) {
			return;
		}
		Location location = chest.getActualLocation();
		if (location == null || location.getWorld() == null) {
			return;
		}
		lootChestLocationIndex.put(
				chest,
				location.getWorld().getUID(),
				location.getBlockX(),
				location.getBlockY(),
				location.getBlockZ());
	}

	public void untrackLootChestLocation(Lootchest chest) {
		if (lootChestLocationIndex != null) {
			lootChestLocationIndex.remove(chest);
		}
	}

	public Lootchest findLootChest(Location location) {
		if (location == null || lootChest == null) {
			return null;
		}

		if (lootChestLocationIndex == null || location.getWorld() == null) {
			return scanLootChest(location);
		}

		boolean verifyIndexedHit = configs != null && configs.debug;
		BlockLocationIndex.Resolution<Lootchest> resolution = lootChestLocationIndex.resolve(
				location.getWorld().getUID(),
				location.getBlockX(),
				location.getBlockY(),
				location.getBlockZ(),
				chest -> isRegisteredAt(chest, location),
				() -> scanLootChest(location),
				verifyIndexedHit);
		if (verifyIndexedHit && resolution.mismatch()) {
			warnLocationIndexMismatch(location, resolution.indexedValue(), resolution.value());
		}
		return resolution.value();
	}

	private Lootchest scanLootChest(Location location) {
		for (Lootchest chest : lootChest.values()) {
			Location chestLocation = chest.getActualLocation();
			if (chestLocation != null && chestLocation.equals(location)) {
				return chest;
			}
		}
		return null;
	}

	private boolean isRegisteredAt(Lootchest chest, Location location) {
		return chest != null
				&& lootChest.get(chest.getName()) == chest
				&& location.equals(chest.getActualLocation());
	}

	private void warnLocationIndexMismatch(Location location, Lootchest indexedChest, Lootchest scannedChest) {
		String warningKey = location.getWorld().getUID()
				+ ":" + location.getBlockX()
				+ ":" + location.getBlockY()
				+ ":" + location.getBlockZ();
		if (locationIndexMismatchWarnings.add(warningKey)) {
			getLogger().warning(
					"Guarded location index mismatch at " + warningKey
							+ ": scan=" + chestName(scannedChest)
							+ ", index=" + chestName(indexedChest)
							+ ". The scan result was used and the index was repaired.");
		}
	}

	private String chestName(Lootchest chest) {
		return chest == null ? "none" : chest.getName();
	}

	private void spawnLoadedChest(Lootchest chest, boolean forceSpawn) {
		if (forceSpawn) {
			chest.spawn(true);
			return;
		}
		if (!chest.spawn(false)) {
			LootChestUtils.scheduleReSpawn(chest);
			chest.reactivateEffects();
		}
	}
	
	
	/**
	* In many versions, I add some text a config option.
	* These lines are done to update config and language files without erasing options that are already set
	*/
	private void updateOldConfig() {
		CompatibilityMigrations.migrateConfig(configFiles.getConfig());
		CompatibilityMigrations.migrateLanguage(configFiles.getLang());
		boolean savedChestDataChanged =
				CompatibilityMigrations.migrateSavedChestData(configFiles.getData());
		configFiles.setConfig("spawn_on_non_solid_blocks", false);
		configFiles.setConfig("Minimum_Height_For_Random_Spawn", 0);
		configFiles.setConfig("Max_Height_For_Random_Spawn", 200);
		configFiles.setConfig("Max_Filled_Slots_By_Default", 0);
		configFiles.setConfig("SaveDataFileDuringReload", true);
		configFiles.setConfig("respawn_notify.respawn_all_with_command_in_world.enabled", true);
		configFiles.setConfig("respawn_notify.respawn_all_with_command_in_world.message", "<#a6e3a1>All LootChests were force-respawned in <#89dceb>[World]<#a6e3a1>.");
		configFiles.setConfig("respawn_notify.Minimum_Number_Of_Players_For_Natural_Spawning", 0);
		configFiles.setConfig("Particles.fallback_particle", "FLAME");
		configFiles.setConfig("Scheduler.Chests_Per_Tick", 1);
		configFiles.setLang("Menu.particles.selected", "<#a6e3a1>Currently selected");
		configFiles.setLang("info.title", "<#cba6f7><bold>Lootbox</bold> <#6c7086>v[Version]");
		configFiles.setLang("info.release", "<#a6e3a1>Build <#89dceb>[Build] <#6c7086>| <#bac2de>[Artifact]");
		configFiles.setLang("info.source", "<#a6e3a1>Source <#89dceb>[Source]");
		configFiles.setLang("info.target", "<#a6e3a1>Targets <#89dceb>Paper [Paper] <#6c7086>(API [PaperApi]) <#a6e3a1>and <#89dceb>Java [Java]");
		configFiles.setLang("info.paper_release", "<#a6e3a1>Paper release <#89dceb>[Paper] build [PaperBuild] [PaperChannel]");
		configFiles.setLang("info.holograms", "<#a6e3a1>Holograms <#89dceb>[Holograms]");
		configFiles.setLang("info.introduction", "<#bac2de>Discover repeatable loot containers with rewards configured for 1MoreBlock.");
		configFiles.setLang("info.commands", "<#a6e3a1>Start with <#89dceb>/lc locate <#a6e3a1>when your rank grants access, or use <#89dceb>/lc help<#a6e3a1>.");
		configFiles.setLang("info.documentation", "<click:open_url:'https://docs.1moreblock.com/custom-server-plugins/lootbox/'><hover:show_text:'Open the Lootbox guide'><#89dceb><underlined>docs.1moreblock.com/custom-server-plugins/lootbox/</underlined></#89dceb></hover></click>");
		configFiles.setLang("audit.title", "<#cba6f7><bold>Lootbox lifecycle audit</bold>");
		configFiles.setLang("audit.summary", "<#a6e3a1>Loaded <#89dceb>[Total] <#bac2de>| <#a6e3a1>present <#89dceb>[Present] <#bac2de>| <#a6e3a1>absent <#89dceb>[Absent] <#bac2de>| <#a6e3a1>wrong <#89dceb>[Wrong] <#bac2de>| <#a6e3a1>unavailable <#89dceb>[Unavailable] <#bac2de>| <#a6e3a1>issues <#89dceb>[Issues]");
		configFiles.setLang("audit.index", "<#a6e3a1>Location index <#89dceb>[Indexed]/[Total]");
		configFiles.setLang("audit.clean", "<#a6e3a1>No lifecycle inconsistencies were found.");
		configFiles.setLang("audit.finding", "<#f6c177>- <#f38ba8>[Code] <#89dceb>[Chest]<#cdd6f4>: [Detail]");
		configFiles.setLang("audit.truncated", "<#f9e2af>[Remaining] additional findings were omitted to keep the report readable.");
		configFiles.setLang("audit.read_only", "<#bac2de>Read-only audit complete; no chest, display, task, configuration, or saved data was changed.");
		configFiles.setLang("audit.click_to_tp", "<#a6e3a1>Click to teleport to <#89dceb>[Chest]");
		configFiles.setLang("audit.target_title", "<#cba6f7><bold>Lootbox audit:</bold> <#89dceb>[Chest]");
		configFiles.setLang("audit.target_container", "<#a6e3a1>Container <#bac2de>saved <#89dceb>[Expected]<#bac2de>, live <#89dceb>[Actual]<#bac2de>, state <#89dceb>[State]");
		configFiles.setLang("audit.target_location", "<#a6e3a1>Location <#89dceb>[Location] <#bac2de>| <#a6e3a1>index <#89dceb>[Index]");
		configFiles.setLang("audit.target_effects", "<#a6e3a1>Hologram <#bac2de>expected <#89dceb>[HologramExpected]<#bac2de>, active <#89dceb>[HologramActive] <#bac2de>| <#a6e3a1>particle <#bac2de>expected <#89dceb>[ParticleExpected]<#bac2de>, active <#89dceb>[ParticleActive]");
		configFiles.setLang("audit.target_task", "<#a6e3a1>Respawn task <#bac2de>expected <#89dceb>[TaskExpected]<#bac2de>, active <#89dceb>[TaskActive]");
		configFiles.setLang(MENU_MAIN_TYPE, "<#cba6f7>Select container type");
		configFiles.setLang("notAnInteger", "<#f38ba8>[Number] is not a whole number.");
		configFiles.setLang("blockIsAlreadyLootchest", "<#f38ba8>This block is already registered as a LootChest.");
		configFiles.setLang("editedMaxFilledSlots", "<#a6e3a1>Maximum filled slots updated for <#89dceb>[Chest]<#a6e3a1>.");
		configFiles.setLang("copiedChest", "<#f6c177>Copied <#89dceb>[Chest1] <#f6c177>into <#89dceb>[Chest2]<#f6c177>.");
		configFiles.setLang("NotEnoughPlayers", "<#f38ba8>At least <#f9e2af>[Number] players <#f38ba8>are required to spawn LootChests.");
		configFiles.setLang("ChestDespawned", "<#a6e3a1>Despawned <#89dceb>[Chest]<#a6e3a1>.");
		configFiles.setLang("NoChestAtLocation", "<#f38ba8>That LootChest is already absent.");
		configFiles.setLang("AllChestsDespawned", "<#a6e3a1>All LootChests were despawned.");
		configFiles.setLang("AllChestsDespawnedInWorld", "<#a6e3a1>All LootChests were despawned in <#89dceb>[World]<#a6e3a1>.");
		configFiles.setLang("ChestOperationInProgress", "<#f9e2af>LootChest is still loading or processing another bulk chest command. Please try again in a moment.");
		configFiles.setLang("ListCommandHover", "<#a6e3a1>Click to edit <#89dceb>[Chest]");
		configFiles.setLang("worldDoesntExist", "<#f38ba8>World <#89dceb>[World] <#f38ba8>does not exist.");
		configFiles.setLang("AllChestsReloadedInWorld", "<#a6e3a1>All LootChests were respawned in <#89dceb>[World]<#a6e3a1>.");
		if(!configFiles.getLang().getStringList("help").toString().contains("despawnall")){
			List<String> help = configFiles.getLang().getStringList("help");
			help.add("<#a6e3a1>/lc despawnall <#bac2de>[world] <#6c7086>- Despawn all LootChests");
			configFiles.getLang().set("help", help);
			configFiles.saveLang();
		}
		if(!configFiles.getLang().getStringList("help").toString().contains("copy")){
			List<String> help = configFiles.getLang().getStringList("help");
			help.add("<#a6e3a1>/lc copy <#bac2de>\\<source> \\<destination> <#6c7086>- Copy one LootChest into another");
			configFiles.getLang().set("help", help);
			configFiles.saveLang();
		}
		if(!configFiles.getLang().getStringList("help").toString().contains("maxfilledslots")){
			List<String> help = configFiles.getLang().getStringList("help");
			help.add("<#a6e3a1>/lc maxfilledslots <#bac2de>\\<name> \\<number> <#6c7086>- Limit filled slots");
			configFiles.getLang().set("help", help);
			configFiles.saveLang();
		}
		if(!configFiles.getLang().getStringList("help").toString().contains("/lc info")){
			List<String> help = configFiles.getLang().getStringList("help");
			help.add(2, "<#a6e3a1>/lc info <#6c7086>- About Lootbox and its documentation");
			configFiles.getLang().set("help", help);
			configFiles.saveLang();
		}
		if(!configFiles.getLang().getStringList("help").toString().contains("/lc audit")){
			List<String> help = configFiles.getLang().getStringList("help");
			help.add(Math.min(2, help.size()), "<#a6e3a1>/lc audit <#bac2de>[chest] <#6c7086>- Inspect all Lootboxes or one named Lootbox");
			configFiles.getLang().set("help", help);
			configFiles.saveLang();
		}
		List<String> commandHelp = configFiles.getLang().getStringList("help");
		for (int i = 0; i < commandHelp.size(); i++) {
			if (commandHelp.get(i).contains("/lc settime")) {
				commandHelp.set(i, "<#a6e3a1>/lc settime <#bac2de>\\<name> \\<minutes> <#6c7086>- Set respawn time");
			}
		}
		configFiles.getLang().set("help", commandHelp);
		if(!configFiles.getLang().getStringList("help").toString().contains("despawn ")){
			  List<String> help = configFiles.getLang().getStringList("help");
			  help.add("<#a6e3a1>/lc despawn <#bac2de>\\<name> <#6c7086>- Despawn a LootChest");
			  configFiles.getLang().set("help", help);
			  configFiles.saveLang();
			}
		configFiles.saveLang();
		configFiles.saveConfig();
		if (savedChestDataChanged) {
			configFiles.saveData();
		}

	}
	

	
	/**
	 * Builds the editor choices from payload-free particles exposed by the running Paper API.
	 */
	private void initParticles() {
		particleCatalog = new ParticleCatalog(configs.partFallbackParticle, getLogger()::warning);
		particles.clear();
		particles.putAll(particleCatalog.getSupportedParticles());
		Messages.log("<#a6e3a1>Loaded " + particles.size() + " Paper particles; fallback: "
				+ particleCatalog.getFallback().name() + ".");
	}

	public void reloadParticleCatalog() {
		failedParticles.clear();
		initParticles();
	}


}
