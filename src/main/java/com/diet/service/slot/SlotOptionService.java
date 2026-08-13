package com.diet.service.slot;

import com.diet.exception.DietException;
import com.diet.mapper.SlotOptionMapper;
import com.diet.model.SlotBundle;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SlotOptionService {
    public static final List<String> SLOT_NAMES = List.of(
            "mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience"
    );

    /**
     * 槽位字典缓存的唯一 key。
     * 调用方永远需要全量 Map，没有按单个 slotName 取值的场景，因此缓存整份而非 7 份。
     */
    private static final String ALL_OPTIONS_KEY = "ALL";

    private final SlotOptionMapper slotOptionMapper;

    /**
     * 槽位字典缓存。
     * diet_slot_option 没有任何写接口，变更只能由运营直接改库，应用层感知不到，
     * 因此只能靠 TTL 做最终一致，不存在精确 evict 的时机。
     */
    private final Cache<String, Map<String, List<String>>> optionsCache;

    public SlotOptionService(
            SlotOptionMapper slotOptionMapper,
            @Value("${diet.cache.slot-options-ttl-minutes:10}") long slotOptionsTtlMinutes
    ) {
        this.slotOptionMapper = slotOptionMapper;
        this.optionsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(0, slotOptionsTtlMinutes)))
                .maximumSize(1)
                .recordStats()
                .build();
    }

    /**
     * 返回全部槽位字段的合法候选值。
     * 结果不可变：缓存后所有调用方共享同一实例，任何修改都会污染全局。
     */
    public Map<String, List<String>> findAllOptions() {
        return optionsCache.get(ALL_OPTIONS_KEY, key -> loadAllOptions());
    }

    /** 缓存命中率等统计，供调试和验证缓存是否生效。 */
    public CacheStats cacheStats() {
        return optionsCache.stats();
    }

    /** 从 DB 读取全部槽位字典，并包装成两层不可变结构（外层 Map + 内层 List）。 */
    private Map<String, List<String>> loadAllOptions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            // List.copyOf 保护 value：Map.copyOf 是浅拷贝，挡不住对内部 List 的修改
            result.put(slotName, List.copyOf(slotOptionMapper.findEnabledValues(slotName)));
        }
        return Collections.unmodifiableMap(result);
    }

    public void validate(SlotBundle slots) {
        Map<String, List<String>> options = findAllOptions();
        validateSlot("mealTime", slots.mealTime(), options);
        validateSlot("mood", slots.mood(), options);
        validateSlot("scene", slots.scene(), options);
        validateSlot("healthGoal", slots.healthGoal(), options);
        validateSlot("cuisine", slots.cuisine(), options);
        validateSlot("taste", slots.taste(), options);
        validateSlot("convenience", slots.convenience(), options);
    }

    private void validateSlot(String slotName, List<String> values, Map<String, List<String>> options) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> allowed = Set.copyOf(options.getOrDefault(slotName, List.of()));
        for (String value : values) {
            if (!allowed.contains(value)) {
                throw new DietException("非法槽位标签: " + slotName + "=" + value);
            }
        }
    }
}
