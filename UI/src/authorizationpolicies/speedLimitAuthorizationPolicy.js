(function(root) {
  root.SpeedLimitAuthorizationPolicy = function() {
    AuthorizationPolicy.call(this);

    var me = this;

    this.formEditModeAccess = function(selectedAsset) {
      return (me.isMunicipalityMaintainer() && !me.isState(selectedAsset) && me.hasRightsInMunicipality(selectedAsset.municipalityCode)) || (me.isElyMaintainer() && me.hasRightsInMunicipality(selectedAsset.municipalityCode)) || me.isOperator();
    };

  };
})(this);