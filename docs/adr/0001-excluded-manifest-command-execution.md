# Excluded command execution from manifests

Archetype manifests describe gameplay behavior declaratively and must remain easy for humans and AI agents to understand. We excluded operations such as `execute_command`, accepting that a missing game interaction may require a framework extension instead of a command workaround. This keeps command syntax and its execution context out of the manifest language.
