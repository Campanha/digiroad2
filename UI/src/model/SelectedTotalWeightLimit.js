(function(root) {
  root.SelectedTotalWeightLimit = function(backend, collection) {
    var current = null;
    var self = this;
    var dirty = false;
    var originalTotalWeightLimit = null;
    var originalExpired = null;

    eventbus.on('totalWeightLimit:split', function() {
      collection.fetchTotalWeightLimit(null, function(totalWeightLimit) {
        current = totalWeightLimit;
        originalTotalWeightLimit = totalWeightLimit.limit;
        dirty = true;
        eventbus.trigger('totalWeightLimit:selected', self);
      });
    });

    this.open = function(id) {
      self.close();
      collection.fetchTotalWeightLimit(id, function(totalWeightLimit) {
        current = totalWeightLimit;
        originalTotalWeightLimit = totalWeightLimit.limit;
        originalExpired = totalWeightLimit.expired;
        collection.markAsSelected(totalWeightLimit.id);
        eventbus.trigger('totalWeightLimit:selected', self);
      });
    };

    this.create = function(roadLinkId) {
      self.close();
      current = {
        isRoadLink: true,
        roadLinkId: roadLinkId
      };
    };

    this.close = function() {
      if (current && !dirty) {
        var id = current.id;
        if (id) {
          collection.markAsDeselected(id);
        }
        if (current.expired) {
          collection.remove(id);
        }
        current = null;
        eventbus.trigger('totalWeightLimit:unselected', id);
      }
    };

    this.saveSplit = function() {
      collection.saveSplit();
    };

    this.cancelSplit = function() {
      var id = current.id;
      current = null;
      dirty = false;
      collection.cancelSplit();
      eventbus.trigger('totalWeightLimit:unselected', id);
    };

    this.save = function() {
      var success = function(totalWeightLimit) {
        dirty = false;
        current = _.merge({}, current, totalWeightLimit);
        originalTotalWeightLimit = current.limit;
        originalExpired = current.expired;
        eventbus.trigger('totalWeightLimit:saved', current);
      };
      var failure = function() {
        eventbus.trigger('asset:updateFailed');
      };

      if (current.expired) {
        expire(success, failure);
      } else {
        update(success, failure);
      }
    };

    var expire = function(success, failure) {
      backend.expireTotalWeightLimit(current.id, success, failure);
    };

    var update = function(success, failure) {
      backend.updateTotalWeightLimit(current.id, current.limit, success, failure);
    };

    this.cancel = function() {
      current.limit = originalTotalWeightLimit;
      current.expired = originalExpired;
      collection.changeLimit(current.id, originalTotalWeightLimit);
      collection.changeExpired(current.id, originalExpired);
      dirty = false;
      eventbus.trigger('totalWeightLimit:cancelled', self);
    };

    this.exists = function() {
      return current !== null;
    };

    this.isRoadLink = function() {
      return current.isRoadLink;
    };

    this.getId = function() {
      return current.id;
    };

    this.getRoadLinkId = function() {
      return current.roadLinkId;
    };

    this.getEndpoints = function() {
      return current.endpoints;
    };

    this.getLimit = function() {
      return current.limit;
    };

    this.expired = function() {
      return current.expired;
    };

    this.getModifiedBy = function() {
      return current.modifiedBy;
    };

    this.getModifiedDateTime = function() {
      return current.modifiedDateTime;
    };

    this.getCreatedBy = function() {
      return current.createdBy;
    };

    this.getCreatedDateTime = function() {
      return current.createdDateTime;
    };

    this.get = function() {
      return current;
    };

    this.setLimit = function(limit) {
      if (limit != current.limit) {
        collection.changeLimit(current.id, limit);
        current.limit = limit;
        dirty = true;
        eventbus.trigger('totalWeightLimit:limitChanged', self);
      }
    };

    this.setExpired = function(expired) {
      if (expired != current.expired) {
        collection.changeExpired(current.id, expired);
        current.expired = expired;
        dirty = true;
        eventbus.trigger('totalWeightLimit:expirationChanged', self);
      }
    };

    this.isDirty = function() {
      return dirty;
    };

    this.isNew = function() {
      return current.id === null;
    };

    eventbus.on('totalWeightLimit:saved', function(totalWeightLimit) {
      current = totalWeightLimit;
      originalTotalWeightLimit = totalWeightLimit.limit;
      originalExpired = totalWeightLimit.expired;
      collection.markAsSelected(totalWeightLimit.id);
      dirty = false;
    });
  };
})(this);
