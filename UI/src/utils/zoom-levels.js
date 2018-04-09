(function() {
  window.zoomlevels = {
    isInRoadLinkZoomLevel: function(zoom) {
      return zoom >= this.minZoomForRoadLinks;
    },
    isInAssetZoomLevel: function(zoom) {
      return zoom >= this.minZoomForAssets;
    },
    getAssetZoomLevelIfNotCloser: function(zoom) {
      return zoom < 10 ? 10 : zoom;
    },
    shouldShowAssets: function (layerName, zoom) {
      return layerName === 'maintenanceRoad' && Math.round(zoom) >= 2 && Math.round(zoom) < 10;
    },
    minZoomForAssets: 10,
    minZoomForRoadLinks: 10,
    maxZoomLevel: 12
  };
})();