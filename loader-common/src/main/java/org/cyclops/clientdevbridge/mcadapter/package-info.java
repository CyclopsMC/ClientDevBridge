/**
 * All version-sensitive Minecraft internals live here; see {@code README.md} in this package.
 *
 * The protocol layer, the handlers, and the CLI must not contain per-Minecraft-version logic,
 * so that porting a branch means porting this package and the mixins, and nothing else.
 */
package org.cyclops.clientdevbridge.mcadapter;
