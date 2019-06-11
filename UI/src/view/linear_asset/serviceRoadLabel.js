(function(root) {

  root.ServiceRoadLabel = function() {
    AssetLabel.call(this);
    var me = this;

    var backgroundStyle = function (value) {
      var imageSrc = validateValue(value);

      return new ol.style.Style({
        image: new ol.style.Icon(({
          src: imageSrc
        }))
      });
    };
    
    this.defaultStyle = function(value){
      return [backgroundStyle(value), new ol.style.Style({
        text : new ol.style.Text({
          text : validateText(value),
          fill: new ol.style.Fill({
            color: '#ffffff'
          }),
          font : '12px sans-serif'
        })
      })];
    };

    this.getValue = function(asset){
      return asset.value;
    };

    var obtainValue = function(value){
      var property = _.find(value.properties, function(val) { return val.publicId === 'huoltotie_tarkistettu'; });
      if(property)
        return _.head(property.values).value;
      return 0;
    };

    var validateValue = function (value) {
      return (obtainValue(value) == 1) ? 'images/linearLabel_largeText_blue.png' : 'images/linearLabel_largeText.png';
    };

    var validateText = function(value){
      return (obtainValue(value) == 1) ?  'Tarkistettu' : 'Ei tarkistettu';
    };

    this.isVisibleZoom = function(zoomLevel){
      return zoomLevel >= 10;
    };
  };
})(this);