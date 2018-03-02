(function(root) {
  root.GroupedPointAssetsCollection = function(backend, spec) {
    var isComplementaryActive = false;
    var isAllowComplementary = false;
    var me = this;

    this.filterComplementaries = function (assets) {
      if(isComplementaryActive || !isAllowComplementary )
        return assets;
      return _.filter(assets, function(asset) { return asset.linkSource != 2; });
    };

    this.fetch = function(boundingBox) {
      return backend.getGroupedPointAssetsWithComplementary(boundingBox, spec.typeIds)
        .then(function(assets) {
          eventbus.trigger('pointAssets:fetched');
          me.allowComplementaryIsActive(spec.allowComplementaryLinks);
          return me.filterComplementaries(assets);
        });
    };

    this.allowComplementaryIsActive = function(allowComplementary) {
      isAllowComplementary = allowComplementary;
    };

    this.activeComplementary = function(enable) {
      isComplementaryActive = enable;
    };

    this.complementaryIsActive= function() {
      return isComplementaryActive;
    };

  };
})(this);