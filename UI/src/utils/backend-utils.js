(function (root) {
  root.Backend = function() {
    var self = this;
    this.getEnumeratedPropertyValues = function (assetTypeId) {
      $.getJSON('api/enumeratedPropertyValues/' + assetTypeId, function (enumeratedPropertyValues) {
        eventbus.trigger('enumeratedPropertyValues:fetched', enumeratedPropertyValues);
      })
        .fail(function () {
          console.log("error");
        });
    };

    this.getRoadLinks = _.throttle(function(boundingBox, callback) {
      $.getJSON('api/roadlinks?bbox=' + boundingBox, function(data) {
        callback(data);
      });
    }, 1000);

    this.getManoeuvres = _.throttle(function(boundingBox, callback) {
      $.getJSON('api/manoeuvre?bbox=' + boundingBox, function(data) {
        callback(data);
      });
    }, 1000);

    this.getRoadLinkByMMLId = _.throttle(function(mmlId, callback) {
      $.getJSON('api/roadlinks/' + mmlId + '?mmlId=true', function(data) {
        callback(data);
      });
    }, 1000);

    this.getAssets = function (boundingBox) {
      self.getAssetsWithCallback(boundingBox, function (assets) {
        eventbus.trigger('assets:fetched', assets);
      });
    };

    this.getAssetsWithCallback = _.throttle(function(boundingBox, callback) {
      $.getJSON('api/assets?bbox=' + boundingBox, callback)
        .fail(function() { console.log("error"); });
    }, 1000);

    this.getSpeedLimits = _.throttle(function (boundingBox, callback) {
      $.getJSON('api/speedlimits?bbox=' + boundingBox, function (speedLimits) {
        callback(speedLimits);
      });
    }, 1000);

    this.getSpeedLimit = _.throttle(function(id, callback) {
      $.getJSON('api/speedlimits/' + id, function(speedLimit) {
        callback(speedLimit);
      });
    }, 1000);

    this.updateSpeedLimit = _.throttle(function(id, limit, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/speedlimits/" + id,
        data: JSON.stringify({limit: limit}),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

     this.updateSpeedLimits = _.throttle(function(ids, value, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/speedlimits",
        data: JSON.stringify({value: value, ids: ids}),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.updateLinkProperties = _.throttle(function(id, data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/linkproperties/" + id,
        data: JSON.stringify({trafficDirection: data.trafficDirection, functionalClass: data.functionalClass, linkType: data.linkType}),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.splitSpeedLimit = function(id, roadLinkId, splitMeasure, limit, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/speedlimits/" + id,
        data: JSON.stringify({roadLinkId: roadLinkId, splitMeasure: splitMeasure, limit: limit}),
        dataType: "json",
        success: success,
        error: failure
      });
    };

    this.getNumericalLimits = _.throttle(function(boundingBox, typeId, callback) {
      $.getJSON('api/numericallimits?typeId=' + typeId + '&bbox=' + boundingBox, function(numericalLimits) {
        callback(numericalLimits);
      });
    }, 1000);

    this.getNumericalLimit = _.throttle(function(id, callback) {
      $.getJSON('api/numericallimits/' + id, function(numericalLimit) {
        callback(numericalLimit);
      });
    }, 1000);

    this.updateNumericalLimit = _.throttle(function(id, value, success, failure) {
      putUpdateNumericalLimitCall(id, {value: value}, success, failure);
    }, 1000);

    this.expireNumericalLimit = _.throttle(function(id, success, failure) {
      putUpdateNumericalLimitCall(id, {expired: true}, success, failure);
    }, 1000);

    var putUpdateNumericalLimitCall = function(id, data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/numericallimits/" + id,
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    };

    this.createNumericalLimit = _.throttle(function(typeId, roadLinkId, value, success, error) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/numericallimits?typeId=" + typeId,
        data: JSON.stringify({roadLinkId: roadLinkId, value: value}),
        dataType: "json",
        success: success,
        error: error
      });
    }, 1000);

    this.splitNumericalLimit = function(id, roadLinkId, splitMeasure, value, expired, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/numericallimits/" + id,
        data: JSON.stringify({roadLinkId: roadLinkId, splitMeasure: splitMeasure, value: value, expired: expired}),
        dataType: "json",
        success: success,
        error: failure
      });
    };

    this.getAsset = function (assetId) {
      self.getAssetWithCallback(assetId, function (asset) {
        eventbus.trigger('asset:fetched', asset);
      });
    };

    this.getAssetWithCallback = function(assetId, callback) {
      $.get('api/assets/' + assetId, callback);
    };

    this.getAssetByExternalId = function (externalId, callback) {
      $.get('api/assets/' + externalId + '?externalId=true', callback);
    };

    this.getAssetTypeProperties = function (assetTypeId, callback) {
      $.get('api/assetTypeProperties/' + assetTypeId, callback);
    };

    this.getUserRoles = function () {
      $.get('api/user/roles', function (roles) {
        eventbus.trigger('roles:fetched', roles);
      });
    };

    this.getStartupParametersWithCallback = function(callback) {
      var url = 'api/startupParameters';
      $.getJSON(url, callback);
    };

    this.getAssetPropertyNamesWithCallback = function(callback) {
      $.getJSON('api/assetPropertyNames/fi', callback);
    };

    this.getFloatingAssetsWithCallback = function(callback) {
      $.getJSON('api/floatingAssets', callback);
    };

    this.createAsset = function (data, errorCallback) {
      eventbus.trigger('asset:creating');
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/assets",
        data: JSON.stringify(data),
        dataType: "json",
        success: function (asset) {
          eventbus.trigger('asset:created', asset);
        },
        error: errorCallback
      });
    };

    this.updateAsset = function (id, data, successCallback, errorCallback) {
      eventbus.trigger('asset:saving');
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/assets/" + id,
        data: JSON.stringify(data),
        dataType: "json",
        success: successCallback,
        error: errorCallback
      });
    };

    this.withRoadLinkData = function (roadLinkData) {
      self.getRoadLinks = function (boundingBox, callback) {
        callback(roadLinkData);
        eventbus.trigger('roadLinks:fetched');
      };
      return self;
    };

    this.withUserRolesData = function(userRolesData) {
      self.getUserRoles = function () {
        eventbus.trigger('roles:fetched', userRolesData);
      };
      return self;
    };

    this.withEnumeratedPropertyValues = function(enumeratedPropertyValuesData) {
      self.getEnumeratedPropertyValues = function () {
        eventbus.trigger('enumeratedPropertyValues:fetched', enumeratedPropertyValuesData);
      };
      return self;
    };

    this.withStartupParameters = function(startupParameters) {
      self.getStartupParametersWithCallback = function(callback) { callback(startupParameters); };
      return self;
    };

    this.withAssetPropertyNamesData = function(assetPropertyNamesData) {
      self.getAssetPropertyNamesWithCallback = function(callback) { callback(assetPropertyNamesData); };
      return self;
    };

    this.withAssetsData = function(assetsData) {
      self.getAssetsWithCallback = function (boundingBox, callback) {
        callback(assetsData);
      };
      return self;
    };

    this.withAssetData = function(assetData) {
      self.getAssetByExternalId = function (externalId, callback) {
        callback(assetData);
      };
      self.getAssetWithCallback = function(assetId, callback) {
        callback(assetData);
      };
      self.updateAsset = function (id, data, successCallback) {
        eventbus.trigger('asset:saving');
        successCallback(_.defaults(data, assetData));
      };
      return self;
    };

    this.withSpeedLimitsData = function(speedLimitsData) {
      self.getSpeedLimits = function(boundingBox, callback) {
        callback(speedLimitsData);
      };
      return self;
    };

    this.withSpeedLimitConstructor = function(speedLimitConstructor) {
      self.getSpeedLimit = function(id, callback) {
        callback(speedLimitConstructor(id));
      };
      return self;
    };

    this.withSpeedLimitUpdate = function(speedLimitData) {
      self.updateSpeedLimit = function(id, limit, success) {
        success(speedLimitData);
      };
      return self;
    };

    this.withSpeedLimitSplitting = function(speedLimitSplitting) {
      self.splitSpeedLimit = speedLimitSplitting;
      return self;
    };

    this.withPassThroughAssetCreation = function() {
      self.createAsset = function(data) {
        eventbus.trigger('asset:created', data);
      };
      return self;
    };

    this.withAssetCreationTransformation = function(transformation) {
      self.createAsset = function(data) {
        eventbus.trigger('asset:created', transformation(data));
      };
      return self;
    };

    this.withAssetTypePropertiesData = function(assetTypePropertiesData) {
      self.getAssetTypeProperties = function(assetTypeId, callback) {
        callback(assetTypePropertiesData);
      };
      return self;
    };
  };
}(this));
