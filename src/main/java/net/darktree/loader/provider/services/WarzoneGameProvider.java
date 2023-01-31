package net.darktree.loader.provider.services;

import net.darktree.loader.provider.patch.WarzoneEntrypointPatch;
import net.darktree.loader.provider.util.WarzoneLogHandler;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ContactInformationImpl;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.version.StringVersion;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipFile;

public class WarzoneGameProvider implements GameProvider {

	static {
		// here just so that the very first line printer by fabric
		// uses the correct and consistent formatting
		Log.init(new WarzoneLogHandler());
	}

	private static final String[] ENTRYPOINTS = {"net.darktree.warzone.Main"};
	
	private Arguments arguments;
	private String entrypoint;
	private Path launchDir;
	private Path libDir;
	private Path gameJar;
	private boolean development = false;
	private final List<Path> libraries = new ArrayList<>();
	private static final StringVersion gameVersion = new StringVersion("1.0.0");

	// Apply our patches, for the sake of incorporating ModInitializer hooks, or to patch branding.
	private static final GameTransformer TRANSFORMER = new GameTransformer(new WarzoneEntrypointPatch());
	
	@Override
	public String getGameId() {
		return "warzone";
	}

	@Override
	public String getGameName() {
		return "Warzone";
	}

	@Override
	public String getRawGameVersion() {
		// Set the version string of the game, simple as that.
		return gameVersion.getFriendlyString();
	}

	@Override
	public String getNormalizedGameVersion() {
		// Set a SemVer-compliant string so that mods can see if they're compatible with the version being loaded.
		return getRawGameVersion();
	}

	/**
	 * This is where we actually set the game's metadata, including the modid, the version, the author, and any
	 * other relevant metadata to the game.
	 */
	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		HashMap<String, String> info = new HashMap<>();
		info.put("homepage", "darktree.net");
		info.put("issues", "https://github.com/dark-tree/warzone/issues");

		BuiltinModMetadata.Builder metadata =
				new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName(getGameName())
				.addAuthor("magistermaks", info)
				.setContact(new ContactInformationImpl(info))
				.setDescription("The core of the Warzone game.");

		try {
			metadata.addDependency(new ModDependencyImpl(ModDependency.Kind.DEPENDS, "java", Collections.singletonList(">=17")));
		} catch (VersionParsingException e) {
			throw new RuntimeException(e);
		}

		return Collections.singletonList(new BuiltinMod(Collections.singletonList(gameJar), metadata.build()));
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	/**
	 * Get the game's launch directory. This is especially useful if the game has a launcher that
	 * launches it from a specific directory, like Minecraft.
	 * For any game that's run with a `java -jar` command, we can usually just set it to the current working
	 * directory, which can be called with "." or just a filename like "game.jar"
	 */
	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) {
			return Paths.get(".");
		}
		return getLaunchDirectory(arguments);
	}

	@Override
	public boolean isObfuscated() {
		return false;
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return false;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	/**
	 * Where is the game's Jar file?
	 * This is needed because instead of launching the game, you're actually launching Fabric (Knot, specifically).
	 * Fabric needs to know where the game is so Fabric can actually start it.
	 */
	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		this.arguments = new Arguments();
		this.arguments.parse(args);
		
		Map<Path, ZipFile> zipFiles = new HashMap<>();
		
		if(Objects.equals(System.getProperty(SystemProperties.DEVELOPMENT), "true")) {
			development = true;
		}
		
		try {
			String gameJarProperty = System.getProperty(SystemProperties.GAME_JAR_PATH);

			GameProviderHelper.FindResult result;
			if (gameJarProperty == null) {
				gameJarProperty = "./game.jar";
			}

			Path path = Paths.get(gameJarProperty);
			if (!Files.exists(path)) {
				throw new RuntimeException("Game jar configured through " + SystemProperties.GAME_JAR_PATH + " system property doesn't exist");
			}

			result = GameProviderHelper.findFirst(Collections.singletonList(path), zipFiles, true, ENTRYPOINTS);

			if (result == null) {
				return false;
			}
			
			entrypoint = result.name;
			gameJar = result.path;

		} catch (Exception e) {
			e.printStackTrace();
		}
		
		processArgumentMap(arguments);
		return true;
	}

	@Override
	public void initialize(FabricLauncher launcher) {
		try {
			launcher.setValidParentClassPath(Collections.singletonList(Path.of(this.getClass().getProtectionDomain().getCodeSource().getLocation().toURI())));
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}

		Log.init(new WarzoneLogHandler());
		TRANSFORMER.locateEntrypoints(launcher, Collections.singletonList(gameJar));
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return TRANSFORMER;
	}

	/**
	 * Add the game to the classpath, as well as any of the game's dependencies.
	 */
	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		launcher.addToClassPath(gameJar);
		
		for (Path lib : libraries) {
			launcher.addToClassPath(lib);
		}
	}

	/**
	 * Start the game using Fabric Loader.
	 */
	@Override
	public void launch(ClassLoader loader) {
		String targetClass = entrypoint;
		
		try {
			Class<?> c = loader.loadClass(targetClass);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) arguments.toArray());
		} catch (InvocationTargetException e) {
			throw new FormattedException("The game has crashed!", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new FormattedException("Failed to start the game", e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	/**
	 * Gets the arguments being passed to Fabric Loader, so for
	 * ... net.fabricmc.loader.launch.knot.KnotClient --debug true
	 * the state of --debug can be called from here.
	 */
	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];
		return arguments.toArray();
	}
	
	private void processArgumentMap(Arguments arguments) {
		if (!arguments.containsKey("gameDir")) {
			arguments.put("gameDir", getLaunchDirectory(arguments).toAbsolutePath().normalize().toString());
		}
		
		launchDir = Path.of(arguments.get("gameDir"));
		System.out.println("Launch directory is " + launchDir);
		libDir = launchDir.resolve(Path.of("./lib"));
	}

	private static Path getLaunchDirectory(Arguments arguments) {
		return Paths.get(arguments.getOrDefault("gameDir", "."));
	}

}
