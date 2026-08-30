package org.cyclops.clientdevbridge.snapshot;

import com.google.gson.JsonObject;

/**
 * Adds mod-specific detail to what an item stack reports, wherever one is described.
 *
 * The default description is the item id, the count, the hover name and the components' combined
 * {@code toString}. That last one is the problem an extractor exists to solve: a data component of
 * a mod's own type prints as a class name and an identity hash, so the one field that says what is
 * <em>in</em> a container item is unreadable. An Everlasting Abilities bottle full of abilities and
 * an empty one describe identically.
 *
 * The stack is passed rather than the component, because what distinguishes one stack from another
 * is usually a combination -- a component, the damage, the count -- and reading it is the mod's job:
 *
 * <pre>
 * ItemExtractors.register(ItemAbilityBottle.class, (stack, details) -&gt;
 *         details.addProperty("abilities", String.valueOf(stack.get(ABILITY_STORE).getAbilities())));
 * </pre>
 *
 * Registering against an item's base class covers every subclass, exactly as
 * {@link BlockExtractor} does for block entities.
 *
 * @author rubensworks
 */
@FunctionalInterface
public interface ItemExtractor<T> {

    /**
     * @param stack the stack being described; its item is an instance of the registered type
     * @param details the object merged into the description as {@code details}
     */
    void extract(net.minecraft.world.item.ItemStack stack, JsonObject details);

}
