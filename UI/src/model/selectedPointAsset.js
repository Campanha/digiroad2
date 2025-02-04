(function(root) {
  root.SelectedPointAsset = function(backend, assetName, roadCollection) {
    var current = null;
    var dirty = false;
    var originalAsset;
    var endPointName = assetName;
    return {
      open: open,
      getId: getId,
      get: get,
      place: place,
      set: set,
      save: save,
      isDirty: isDirty,
      isNew: isNew,
      cancel: cancel,
      close: close,
      exists: exists,
      isSelected: isSelected,
      getAdministrativeClass: getAdministrativeClass,
      checkSelectedSign: checkSelectedSign,
      setPropertyByPublicId: setPropertyByPublicId,
      getMunicipalityCode: getMunicipalityCode,
      getMunicipalityCodeByLinkId: getMunicipalityCodeByLinkId,
      getCoordinates: getCoordinates,
      setAdditionalPanels: setAdditionalPanels,
      setAdditionalPanel: setAdditionalPanel
    };

    function place(asset) {
      dirty = true;
      current = asset;
      eventbus.trigger(assetName + ':selected');
    }

    function set(asset) {
      dirty = true;
      _.mergeWith(current, asset, function(a, b) {
        if (_.isArray(a)) { return b; }
      });
      eventbus.trigger(assetName + ':changed');
    }

    function open(asset) {
      originalAsset = _.cloneDeep(_.omit(asset, "geometry"));
      current = asset;
      eventbus.trigger(assetName + ':selected');
    }

    function cancel() {
      if (isNew()) {
        reset();
        eventbus.trigger(assetName + ':creationCancelled');
      } else {
        dirty = false;
        current = _.cloneDeep(originalAsset);
        eventbus.trigger(assetName + ':cancelled');
      }
    }

    function reset() {
      dirty = false;
      current = null;
    }

    function getId() {
      return current && current.id;
    }

    function get() {
      return current;
    }

    function exists() {
      return !_.isNull(current);
    }

    function isDirty() {
      return dirty;
    }

    function isNew() {
      return getId() === 0;
    }

    function save() {
      eventbus.trigger(assetName + ':saving');
      current = _.omit(current, 'geometry');
      if (current.toBeDeleted) {
        eventbus.trigger(endPointName + ':deleted', current, 'deleted');
        backend.removePointAsset(current.id, endPointName).done(done).fail(fail);
      } else if (isNew()) {
        eventbus.trigger(endPointName + ':created', current, 'created');
        backend.createPointAsset(current, endPointName).done(done).fail(fail);
      } else {
        eventbus.trigger(endPointName + ':updated', current, 'updated');
        backend.updatePointAsset(current, endPointName).done(done).fail(fail);
      }

      function done() {
        eventbus.trigger(assetName + ':saved');
        close();
      }

      function fail() {
        eventbus.trigger('asset:updateFailed');
      }
    }

    function close() {
      reset();
      eventbus.trigger(assetName + ':unselected');
    }

    function isSelected(asset) {
      return getId() === asset.id;
    }

    function getAdministrativeClass(linkId){
      if(current && current.administrativeClass && !linkId)
        return current.administrativeClass;
      var road = roadCollection.getRoadLinkByLinkId(linkId);
      var administrativeClass = road ? road.getData().administrativeClass : null;
      return _.isNull(administrativeClass) || _.isUndefined(administrativeClass) ? undefined : administrativeClass;

    }

    function getMunicipalityCodeByLinkId(linkId){
      if(current && current.municipalityCode && !linkId)
        return current.municipalityCode;
      var road = roadCollection.getRoadLinkByLinkId(linkId);
      var municipalityCode = road ? road.getData().municipalityCode : null;
      return _.isNull(municipalityCode) || _.isUndefined(municipalityCode) ? undefined : municipalityCode;

    }

    function getMunicipalityCode(){
      return !_.isUndefined(current.municipalityCode) ?  current.municipalityCode: roadCollection.getRoadLinkByLinkId(current.linkId).getData().municipalityCode;
    }

    function getCoordinates(){
      return {lon: current.lon, lat: current.lat};
    }

    function getSelectedTrafficSignValue() {
      return parseInt(_.head(_.find(current.propertyData, function(prop){return prop.publicId === "trafficSigns_type";}).values).propertyValue);
    }

    function checkSelectedSign(trafficSignsShowing){
      if (current && (!_.includes(trafficSignsShowing, getSelectedTrafficSignValue()) &&
        getSelectedTrafficSignValue() !== undefined)) {
        close();
      }
    }

    function setPropertyByPublicId(propertyPublicId, propertyValue) {
      dirty = true;
      _.map(current.propertyData, function (prop) {
        if (prop.publicId === propertyPublicId) {
          prop.values[0] = {propertyValue: propertyValue, propertyDisplayValue: ''};
        }
      });
      eventbus.trigger(assetName + ':changed');
    }

    function setAdditionalPanels(panels) {
      dirty = true;
      _.map(current.propertyData, function (prop) {
        if (prop.publicId === 'additional_panel') {
          prop.values = panels;
        }
      });
      eventbus.trigger(assetName + ':changed');
    }

    function setAdditionalPanel(myobj) {
      dirty = true;
      _.map(current.propertyData, function (prop) {
        if (prop.publicId === 'additional_panel') {
          var index = _.findIndex(prop.values, {formPosition: myobj.formPosition});
          prop.values.splice(index, 1, myobj);
        }
      });
      eventbus.trigger(assetName + ':changed');
    }
  };
})(this);
