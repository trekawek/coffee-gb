package eu.rekawek.coffeegb;

import java.util.Collection;

public class CartridgeOptions {

  private final boolean forceDmg;

  private final boolean forceCgb;

  private final boolean useBootstrap;

  private final boolean disableBatterySaves;

  public CartridgeOptions(Collection<String> params, Collection<String> shortParams) {
    this.forceDmg = params.contains("force-dmg") || shortParams.contains("d");
    this.forceCgb = params.contains("force-cgb") || shortParams.contains("c");
    if (forceDmg && forceCgb) {
      throw new IllegalArgumentException(
          "force-dmg and force-cgb options are can't be used together");
    }
    this.useBootstrap = params.contains("use-bootstrap") || shortParams.contains("b");
    this.disableBatterySaves =
        params.contains("disable-battery-saves") || shortParams.contains("db");
  }

  public boolean isForceDmg() {
    return forceDmg;
  }

  public boolean isForceCgb() {
    return forceCgb;
  }

  public boolean isUsingBootstrap() {
    return useBootstrap;
  }

  public boolean isSupportBatterySaves() {
    return !disableBatterySaves;
  }
}
