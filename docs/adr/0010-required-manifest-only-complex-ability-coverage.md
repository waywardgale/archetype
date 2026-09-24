# Required complex ability composition without routine Kotlin changes

The user required most complex abilities to be authorable out of the box through manifests. We made a concrete composition catalog and scenario matrix part of v1 acceptance. Adding phases, target chains, areas, procs, temporary forms, or combinations of supported mechanics must not require a handler written for that ability.

This increases the framework's built-in work: it must own correlation, bounded history, timer replacement, result accounting, contribution lifetimes, and cleanup. We accepted that work to keep author manifests readable and content iteration independent from mod rebuilds. Common gaps call for reusable operations, while genuinely new engine-level interactions or rendering capabilities still use Kotlin extensions.

The catalog retains typed operations and bounded execution rather than adding a script or command escape hatch. Conformance fixtures and authoring references do not become bundled classes. The scenario matrix must demonstrate the contract during implementation; documentation and YAML syntax checks alone do not establish working coverage.
