<template>
  <div class="layers-menu">
    <p class="layers-help">{{ $t("layers.help") }}</p>
    <SwitchButton :on="layers.enabled" @action="apply(!layers.enabled, layers.minY, layers.maxY, true)">{{ $t("layers.enabled") }}</SwitchButton>
    <Group :title="$t('layers.range')">
      <Slider :value="layers.maxY" :min="limits.min" :max="limits.max" :step="1"
              @update="apply(true, layers.minY, Math.max($event, layers.minY))" @lazy="save()">{{ $t("layers.maxY") }}</Slider>
      <Slider :value="layers.minY" :min="limits.min" :max="limits.max" :step="1"
              @update="apply(true, Math.min($event, layers.maxY), layers.maxY)" @lazy="save()">{{ $t("layers.minY") }}</Slider>
      <div class="layers-inputs">
        <label>{{ $t("layers.from") }} <input type="number" :value="layers.minY" :min="limits.min" :max="limits.max" @change="apply(true, Number($event.target.value), layers.maxY, true)"></label>
        <label>{{ $t("layers.to") }} <input type="number" :value="layers.maxY" :min="limits.min" :max="limits.max" @change="apply(true, layers.minY, Number($event.target.value), true)"></label>
      </div>
    </Group>
    <SimpleButton @action="apply(false, limits.min, limits.max, true)">{{ $t("layers.reset") }}</SimpleButton>
  </div>
</template>

<script>
import Group from "./Group.vue";
import Slider from "./Slider.vue";
import SwitchButton from "./SwitchButton.vue";
import SimpleButton from "./SimpleButton.vue";

export default {
  name: "LayersMenu",
  components: {SimpleButton, SwitchButton, Slider, Group},
  data() {
    return {
      layers: this.$bluemap.appState.layers,
    }
  },
  computed: {
    // Overworld -64..319; other dimensions are within this range too.
    limits() {
      return {min: -64, max: 320};
    }
  },
  methods: {
    apply(enabled, minY, maxY, save = false) {
      this.$bluemap.setLayerSlice(enabled, minY, maxY);
      if (save) this.save();
    },
    save() {
      this.$bluemap.saveUserSettings();
    }
  }
}
</script>

<style lang="scss">
.layers-menu {
  .layers-help {
    font-size: 0.8em;
    margin: 0.5em;
    opacity: 0.8;
  }

  .layers-inputs {
    display: flex;
    gap: 0.5em;
    padding: 0.5em;

    label {
      flex: 1;
      display: flex;
      align-items: center;
      gap: 0.3em;
      font-size: 0.9em;
    }

    input {
      width: 100%;
      min-width: 0;
      background: var(--theme-bg-light);
      color: var(--theme-fg);
      border: solid 1px var(--theme-bg-hover);
      padding: 0.2em 0.3em;
    }
  }
}
</style>
