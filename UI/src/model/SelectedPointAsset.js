(function(root) {
  root.SelectedPointAsset = function() {
    var current = null;

    return {
      open: open,
      getId: getId,
      asset: asset
    };

    function open(asset) {
      current = asset;
      eventbus.trigger('pedestrianCrossing:selected');
    }

    function getId() {
      return current.id;
    }

    function asset() {
      return current;
    }
  };
})(this);