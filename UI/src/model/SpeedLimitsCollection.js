(function(root) {
  root.SpeedLimitsCollection = function(backend) {
    var speedLimits = [];
    var unknownSpeedLimits = [];
    var dirty = false;
    var selection = null;
    var self = this;
    var splitSpeedLimits = {};

    var maintainSelectedSpeedLimitChain = function(collection) {
      if (!selection) return collection;
      return _.reject(collection, function (speedLimitGroup) {
        return _.some(speedLimitGroup, function (speedLimit) {
          return selection.isSelected(speedLimit);
        });
      }).concat([selection.get()]);
    };

    this.getAll = function() {
      var existingSplit = _.has(splitSpeedLimits, 'existing') ? [splitSpeedLimits.existing] : [];
      var createdSplit = _.has(splitSpeedLimits, 'created') ? [splitSpeedLimits.created] : [];
      if (selection && selection.isSplit()) {
        throw "Split is not yet implemented for speed limit chains";
        // knowns = _.omit(knowns, splitSpeedLimits.existing.id.toString());
      }
      var allWithSelectedSpeedLimitChain = maintainSelectedSpeedLimitChain(speedLimits.concat(unknownSpeedLimits));
      return allWithSelectedSpeedLimitChain.concat(existingSplit).concat(createdSplit);
    };

    // TODO: Add sidecode to generatedId
    var generateUnknownLimitId = function(speedLimit) {
      return speedLimit.mmlId.toString() +
          speedLimit.startMeasure.toFixed(2) +
          speedLimit.endMeasure.toFixed(2);
    };

    this.fetch = function(boundingBox) {
      backend.getSpeedLimits(boundingBox, function(speedLimitGroups) {
        var partitionedSpeedLimitGroups = _.groupBy(speedLimitGroups, function(speedLimitGroup) {
          return _.some(speedLimitGroup, function(speedLimit) { return _.has(speedLimit, "id"); });
        });
        speedLimits = partitionedSpeedLimitGroups[true] || [];
        unknownSpeedLimits = _.map(partitionedSpeedLimitGroups[false], function(speedLimitGroup) {
          return _.map(speedLimitGroup, function(speedLimit) {
            return _.merge({}, speedLimit, { generatedId: generateUnknownLimitId(speedLimit) });
          });
        }) || [];
        eventbus.trigger('speedLimits:fetched', self.getAll());
      });
    };

    this.fetchSpeedLimit = function(id, callback) {
      backend.getSpeedLimit(id, function(speedLimit) {
        callback(_.merge({}, speedLimits[id], speedLimit));
      });
    };

    this.getUnknown = function(generatedId) {
      return unknownSpeedLimits[generatedId];
    };

    this.getGroupBySegmentId = function(segmentId) {
      return _.find(speedLimits, function(speedLimitGroup) {
        return _.some(speedLimitGroup, { id: segmentId });
      });
    };

    this.getGroupByGeneratedId = function(generatedId) {
      return _.find(unknownSpeedLimits, function(speedLimitGroup) {
        return _.some(speedLimitGroup, { generatedId: generatedId });
      });
    };

    this.createSpeedLimitForUnknown = function(speedLimit) {
      unknownSpeedLimits = _.omit(unknownSpeedLimits, speedLimit.generatedId);
      speedLimits[speedLimit.id] = speedLimit;
    };

    this.setSelection = function(sel) {
      selection = sel;
    };

    this.changeValue = function(id, value) {
      if (splitSpeedLimits.created) {
        splitSpeedLimits.created.value = value;
      } else {
        speedLimits[id].value = value;
      }
    };

    this.updateFromSelection = function() {
      speedLimits = maintainSelectedSpeedLimitChain(speedLimits);
    };

    var calculateMeasure = function(links) {
      var geometries = _.map(links, function(link) {
        var points = _.map(link.points, function(point) {
          return new OpenLayers.Geometry.Point(point.x, point.y);
        });
        return new OpenLayers.Geometry.LineString(points);
      });
      return _.reduce(geometries, function(acc, x) {
        return acc + x.getLength();
      }, 0);
    };

    this.splitSpeedLimit = function(id, mmlId, split, callback) {
      backend.getSpeedLimit(id, function(speedLimit) {
        var speedLimitLinks = speedLimit.speedLimitLinks;
        var splitLink = _.find(speedLimitLinks, function(link) {
          return link.mmlId === mmlId;
        });
        var position = splitLink.position;
        var towardsLinkChain = splitLink.towardsLinkChain;

        var left = _.cloneDeep(speedLimits[id]);
        var right = _.cloneDeep(speedLimits[id]);

        var leftLinks = _.filter(_.cloneDeep(speedLimitLinks), function(it) {
          return it.position < position;
        });

        var rightLinks = _.filter(_.cloneDeep(speedLimitLinks), function(it) {
          return it.position > position;
        });

        left.links = leftLinks.concat([{points: towardsLinkChain ? split.firstSplitVertices : split.secondSplitVertices,
                                        position: position,
                                        mmlId: mmlId}]);

        right.links = [{points: towardsLinkChain ? split.secondSplitVertices : split.firstSplitVertices,
                        position: position,
                        mmlId: mmlId}].concat(rightLinks);

        if (calculateMeasure(left.links) < calculateMeasure(right.links)) {
          splitSpeedLimits.created = left;
          splitSpeedLimits.existing = right;
        } else {
          splitSpeedLimits.created = right;
          splitSpeedLimits.existing = left;
        }

        splitSpeedLimits.created.id = null;
        splitSpeedLimits.splitMeasure = split.splitMeasure;
        splitSpeedLimits.splitMmlId = mmlId;
        dirty = true;
        callback(splitSpeedLimits.created);
        eventbus.trigger('speedLimits:fetched', self.getAll());
      });
    };

    this.saveSplit = function(callback) {
      backend.splitSpeedLimit(splitSpeedLimits.existing.id, splitSpeedLimits.splitMmlId, splitSpeedLimits.splitMeasure, splitSpeedLimits.created.value, function(updatedSpeedLimits) {
        var existingId = splitSpeedLimits.existing.id;
        splitSpeedLimits = {};
        dirty = false;
        delete speedLimits[existingId];

        _.each(updatedSpeedLimits, function(speedLimit) {
          speedLimit.links = speedLimit.speedLimitLinks;
          delete speedLimit.speedLimitLinks;
          speedLimit.sideCode = speedLimit.links[0].sideCode;
          speedLimits[speedLimit.id] = speedLimit;
        });

        var newSpeedLimit = _.find(updatedSpeedLimits, function(speedLimit) { return speedLimit.id !== existingId; });
        callback(newSpeedLimit);

        eventbus.trigger('speedLimits:fetched', self.getAll());

        eventbus.trigger('speedLimit:saved');
        applicationModel.setSelectedTool('Select');
      });
    };

    this.cancelSplit = function() {
      dirty = false;
      splitSpeedLimits = {};
      eventbus.trigger('speedLimits:fetched', self.getAll());
    };

    this.isDirty = function() {
      return dirty;
    };

  };
})(this);
