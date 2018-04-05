(function(root) {
  root.ServiceRoadStyle = function() {
    AssetStyle.call(this);
    var me = this;

    var valueExists = function(asset) {
      return !_.isUndefined(asset.value);
    };

    var findValue = function(asset, publicId) {
      return _.find(asset.value, function(a) { return a.publicId === publicId; }).value
    };

    this.renderOverlays = function(linearAssets) {
      return me.lineFeatures(_.map(linearAssets, function(linearAsset) {
        var expired = _.isUndefined(linearAsset.value);
        return _.merge({}, linearAsset, { type: 'overlay' }, { expired: expired }); }));
    };

    me.renderFeatures = function(linearAssets) {
      return  me.lineFeatures(me.getNewFeatureProperties(linearAssets)).concat(me.renderOverlays(linearAssets));
    };

    var serviceRoadStyleRules = [
      new StyleRule().where('expired').is(true).use({ stroke : { color: '#7f7f7c'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(1).use({stroke: {color: '#0011bb'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(2).use({stroke: {color: '#11bb00'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(3).use({stroke: {color: '#ff69b4'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(4).use({stroke: {color: '#00ccdd'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(6).use({stroke: {color: '#ff982c'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(9).use({stroke: {color: '#ffe82d'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_kayttooikeus"); }}).is(99).use({stroke: {color: '#ff0000'}})
    ];

    var rightOfUseStyleRules = [
      new StyleRule().where('expired').is(true).use({ stroke : { color: '#7f7f7c'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_huoltovastuu"); }}).is(1).use({stroke: {color: '#0011bb'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_huoltovastuu"); }}).is(2).use({stroke: {color: '#11bb00'}}),
      new StyleRule().where(function(asset){if(valueExists(asset)){return findValue(asset, "huoltotie_huoltovastuu"); }}).is(99).use({stroke: {color: '#ff0000'}})
    ];

    var serviceRoadFeatureSizeRules = [
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 3}, pointRadius: 0}),
      new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 5}, pointRadius: 10}),
      new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 7}, pointRadius: 14}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 10}, pointRadius: 16}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 10}, pointRadius: 16}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 14}, pointRadius: 22}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 14}, pointRadius: 22})
    ];

    var overlayStyleRules = [
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(9).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 1,  lineDash: [1,6] }}),
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(10).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 3,  lineDash: [1,10] }}),
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(11).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 5,  lineDash: [1,15] }}),
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(12).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 8,  lineDash: [1,22] }}),
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(13).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 8,  lineDash: [1,22] }}),
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(14).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 12, lineDash: [1,28] }}),
      new StyleRule().where('type').is('overlay').and('zoomLevel').is(15).and('expired').is(false).use({ stroke: {opacity: 1.0, color: '#ffffff', lineCap: 'square', width: 12, lineDash: [1,28] }})
    ];

    me.rightOfUseStyle = new StyleRuleProvider({ stroke : { opacity: 0.7 }});
    me.rightOfUseStyle.addRules(rightOfUseStyleRules);
    me.rightOfUseStyle.addRules(serviceRoadFeatureSizeRules);
    me.rightOfUseStyle.addRules(overlayStyleRules);

    me.browsingStyleProvider = new StyleRuleProvider({ stroke : { opacity: 0.7 }});
    me.browsingStyleProvider.addRules(serviceRoadStyleRules);
    me.browsingStyleProvider.addRules(serviceRoadFeatureSizeRules);

  };
})(this);