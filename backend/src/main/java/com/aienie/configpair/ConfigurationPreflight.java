package com.aienie.configpair;

/** Validate effective ConfigData without creating beans or opening drivers. */
public final class ConfigurationPreflight {
    private ConfigurationPreflight() { }

    public static void main(String[] args) {
        RuntimeConfiguration.initialize(new String[0]);
        if (args.length == 1 && !args[0].equals(RuntimeConfiguration.getenv("ENV")))
            throw new IllegalStateException("Effective ENV does not match this runtime entry point");
        if (args.length == 2 && args[0].equals("--expect-asset-root")) {
            String path = RuntimeConfiguration.getenv("ASSET_STORAGE_ROOT");
            if (!args[1].equals(path))
                throw new IllegalStateException("ASSET_STORAGE_ROOT must match the published seed mount");
        } else if (args.length > 1) throw new IllegalArgumentException("Invalid configuration preflight arguments");
    }
}
