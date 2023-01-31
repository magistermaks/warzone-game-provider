package net.darktree.loader.provider.patch;

import net.darktree.loader.provider.services.WarzoneHooks;
import net.fabricmc.loader.impl.game.patch.GamePatch;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

public class WarzoneEntrypointPatch extends GamePatch {

	@Override
	public void process(FabricLauncher launcher, Function<String, ClassReader> classSource, Consumer<ClassNode> classEmitter) {
		// Get the game's entrypoint (set in the GameProvider) from FabricLauncher
		String entrypoint = launcher.getEntrypoint();

		// Store the entrypoint class as a ClassNode variable so that we can more easily work with it.
		ClassNode mainClass = readClass(classSource.apply(entrypoint));

		// Set the initializer method, this is usually not the main method,
		// it should ideally be placed as close to the game loop as possible without being inside it
		MethodNode initMethod = findMethod(mainClass, (method) -> method.name.equals("main"));

		if (initMethod == null) {
			throw new RuntimeException("Could not find init method in " + entrypoint + "!");
		}

		Log.debug(LogCategory.GAME_PATCH, "Found init method: %s -> %s", entrypoint, mainClass.name);
		Log.debug(LogCategory.GAME_PATCH, "Patching init method %s%s", initMethod.name, initMethod.desc);

		// Modify the list of instructions for our initializer method.
		ListIterator<AbstractInsnNode> instructions = initMethod.instructions.iterator();
		instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WarzoneHooks.INTERNAL_NAME, "init", "()V", false));
		classEmitter.accept(mainClass);
	}

}
