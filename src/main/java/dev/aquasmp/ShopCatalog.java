package dev.aquasmp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

public final class ShopCatalog {
    public enum Section {
        GEAR("Gear", false, Material.DIAMOND_CHESTPLATE),
        NATURE("Nature", false, Material.OAK_SAPLING),
        DECOR("Decorations", false, Material.SEA_LANTERN),
        SHARDS("Shard Shop", true, Material.AMETHYST_SHARD);

        private final String label;
        private final boolean shards;
        private final Material icon;

        Section(String label, boolean shards, Material icon) {
            this.label = label;
            this.shards = shards;
            this.icon = icon;
        }

        public String label() { return label; }
        public boolean usesShards() { return shards; }
        public Material icon() { return icon; }
    }

    public record Product(String id, Section section, Material material, int index) { }

    private static final Map<Section, List<String>> ITEMS = new EnumMap<>(Section.class);

    static {
        ITEMS.put(Section.GEAR, List.of(
            "DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS",
            "DIAMOND_PICKAXE", "DIAMOND_AXE", "DIAMOND_SHOVEL", "DIAMOND_HOE",
            "GOLDEN_APPLE", "GOLDEN_CARROT"
        ));
        ITEMS.put(Section.NATURE, List.of(
            "OAK_SAPLING", "SPRUCE_SAPLING", "BIRCH_SAPLING", "JUNGLE_SAPLING", "ACACIA_SAPLING",
            "DARK_OAK_SAPLING", "CHERRY_SAPLING", "MANGROVE_PROPAGULE", "PALE_OAK_SAPLING",
            "OAK_LEAVES", "SPRUCE_LEAVES", "BIRCH_LEAVES", "JUNGLE_LEAVES", "ACACIA_LEAVES",
            "DARK_OAK_LEAVES", "CHERRY_LEAVES", "MANGROVE_LEAVES", "PALE_OAK_LEAVES",
            "AZALEA", "FLOWERING_AZALEA", "MOSS_BLOCK", "MOSS_CARPET", "VINE", "GLOW_LICHEN",
            "GLOW_BERRIES", "SWEET_BERRIES", "BROWN_MUSHROOM", "RED_MUSHROOM", "CRIMSON_FUNGUS",
            "WARPED_FUNGUS", "CRIMSON_ROOTS", "WARPED_ROOTS", "NETHER_SPROUTS", "WEEPING_VINES",
            "TWISTING_VINES", "LILY_PAD", "CACTUS", "SUGAR_CANE", "BAMBOO", "KELP",
            "SEAGRASS", "SEA_PICKLE", "TUBE_CORAL_BLOCK", "TUBE_CORAL", "BRAIN_CORAL", "HORN_CORAL",
            "FIRE_CORAL"
        ));
        ITEMS.put(Section.DECOR, List.of(
            "SEA_LANTERN", "LANTERN", "SOUL_LANTERN", "GLOWSTONE", "SHROOMLIGHT", "JACK_O_LANTERN",
            "SEA_PICKLE", "END_ROD", "AMETHYST_CLUSTER", "BELL", "COPPER_CHAIN", "IRON_BARS", "GLASS",
            "TINTED_GLASS", "WHITE_STAINED_GLASS", "LIGHT_BLUE_STAINED_GLASS", "BLUE_STAINED_GLASS",
            "CYAN_STAINED_GLASS", "WHITE_CONCRETE", "LIGHT_BLUE_CONCRETE", "BLUE_CONCRETE",
            "CYAN_CONCRETE", "WHITE_TERRACOTTA", "LIGHT_BLUE_TERRACOTTA", "BLUE_TERRACOTTA",
            "CYAN_TERRACOTTA", "PRISMARINE", "DARK_PRISMARINE", "PRISMARINE_BRICKS",
            "CUT_COPPER", "WAXED_CUT_COPPER", "COPPER_GRATE", "WAXED_COPPER_GRATE", "QUARTZ_BLOCK",
            "CHISELED_QUARTZ_BLOCK", "POLISHED_DIORITE", "POLISHED_ANDESITE", "POLISHED_GRANITE",
            "BRICKS", "MUD_BRICKS", "DECORATED_POT", "FLOWER_POT", "ARMOR_STAND", "ITEM_FRAME",
            "GLOW_ITEM_FRAME", "PAINTING", "WHITE_BANNER"
        ));
        ITEMS.put(Section.SHARDS, List.of(
            "AQUA_3X3_PICKAXE", "AQUA_TREE_FELLER", "AQUA_SELL_AXE", "AQUA_TIDEBREAKER"
        ));
    }

    private ShopCatalog() { }

    public static List<Product> products(Section section) {
        List<Product> products = new ArrayList<>();
        List<String> ids = ITEMS.get(section);
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            Material material = materialFor(id);
            if (material != null) {
                products.add(new Product(id, section, material, index));
            }
        }
        return products;
    }

    public static Material materialFor(String id) {
        return switch (id) {
            case "AQUA_3X3_PICKAXE" -> Material.NETHERITE_PICKAXE;
            case "AQUA_TREE_FELLER", "AQUA_SELL_AXE" -> Material.NETHERITE_AXE;
            case "AQUA_TIDEBREAKER" -> Material.NETHERITE_SWORD;
            default -> Material.matchMaterial(id);
        };
    }
}