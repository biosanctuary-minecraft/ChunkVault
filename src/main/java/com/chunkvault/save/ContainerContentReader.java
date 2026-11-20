package com.chunkvault.save;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ContainerContentReader {

    public record DecodedContainer(ItemStack[] slots, int totalSlots, int filledSlots, boolean doubleChest) {}

    private ContainerContentReader() {}

    public static ItemStack[] decodeSingle(ContainerDatabase.ContainerEntry entry, HolderLookup.Provider registries) {
        int size = Math.max(1, entry.slotCount());
        ItemStack[] result = new ItemStack[size];
        Arrays.fill(result, ItemStack.EMPTY);
        if (registries == null || entry.blockEntityTag() == null) {
            return result;
        }
        try {
            NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, entry.blockEntityTag());
            ContainerHelper.loadAllItems(input, items);
            for (int i = 0; i < size; i++) {
                result[i] = items.get(i);
            }
        } catch (Throwable t) {
            System.err.println("[ChunkVault] Failed to decode container " + entry.key() + ": " + t);
        }
        return result;
    }

    public static DecodedContainer decode(ContainerDatabase.ContainerEntry entry, HolderLookup.Provider registries) {
        ContainerDatabase.ContainerEntry partner = ContainerDatabase.partnerOf(entry);
        if (partner != null && entry.slotCount() == 27 && partner.slotCount() == 27) {
            boolean entryIsFirst = "right".equals(entry.chestType())
                    || (!"left".equals(entry.chestType()) && entry.key().compareTo(partner.key()) <= 0);
            ContainerDatabase.ContainerEntry first = entryIsFirst ? entry : partner;
            ContainerDatabase.ContainerEntry second = entryIsFirst ? partner : entry;
            ItemStack[] a = decodeSingle(first, registries);
            ItemStack[] b = decodeSingle(second, registries);
            ItemStack[] merged = new ItemStack[54];
            System.arraycopy(a, 0, merged, 0, 27);
            System.arraycopy(b, 0, merged, 27, 27);
            return new DecodedContainer(merged, 54, countFilled(merged), true);
        }
        ItemStack[] slots = decodeSingle(entry, registries);
        return new DecodedContainer(slots, slots.length, countFilled(slots), false);
    }

    public static boolean isShulkerStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.get(DataComponents.CONTAINER) != null) {
            return true;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.endsWith("shulker_box");
    }

    public static ItemStack[] decodeNestedShulker(ItemStack stack) {
        ItemStack[] slots = new ItemStack[27];
        Arrays.fill(slots, ItemStack.EMPTY);
        if (stack == null || stack.isEmpty()) {
            return slots;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return slots;
        }
        List<ItemStack> all = contents.allItemsCopyStream().toList();
        int n = Math.min(27, all.size());
        for (int i = 0; i < n; i++) {
            ItemStack s = all.get(i);
            slots[i] = s == null ? ItemStack.EMPTY : s;
        }
        return slots;
    }

    public static List<ItemStack> collectAllItemsIncludingNested(
            ContainerDatabase.ContainerEntry entry, HolderLookup.Provider registries) {
        DecodedContainer decoded = decode(entry, registries);
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : decoded.slots()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            out.add(stack);
            ItemContainerContents nested = stack.get(DataComponents.CONTAINER);
            if (nested != null) {
                nested.nonEmptyItemCopyStream().forEach(out::add);
            }
        }
        return out;
    }

    public static String itemSearchText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
        return name + " " + id;
    }

    private static int countFilled(ItemStack[] slots) {
        int filled = 0;
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) {
                filled++;
            }
        }
        return filled;
    }
}
