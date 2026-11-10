package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.install.Installer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "update",
        aliases = {"upgrade", "up"},
        mixinStandardHelpOptions = true,
        description = "Update addons to the newest release matching wowfam.toml and record it in wowfam.lock.")
final class UpdateCommand implements Callable<Integer> {

    @ParentCommand
    WowfamCommand root;

    @Spec
    CommandSpec spec;

    @Parameters(arity = "0..*", paramLabel = "ADDON", description = "Addon names from wowfam.toml.")
    List<String> addons = new ArrayList<>();

    @Override
    public Integer call() {
        return Reports.print(spec, new Installer(root.backends(), spec.commandLine().getOut()::println)
                .run(root.loadConfig(), addons, Installer.Mode.UPDATE, false));
    }
}
