(function(root) {
  root.SelectedProjectLink = function(projectLinkCollection) {

    var current = [];
    var ids = [];
    var dirty = false;
    var splitSuravage = {};
    var LinkGeomSource = LinkValues.LinkGeomSource;
    var preSplitData = null;

    var open = function (linkid, multiSelect) {
      if (!multiSelect) {
        current = projectLinkCollection.getByLinkId([linkid]);
        ids = [linkid];
      } else {
        ids = projectLinkCollection.getMultiSelectIds(linkid);
        current = projectLinkCollection.getByLinkId(ids);
      }
      eventbus.trigger('projectLink:clicked', get(linkid));
    };

    var orderSplitParts = function(links) {
      var splitLinks =  _.partition(links, function(link){
        return link.roadLinkSource === LinkGeomSource.SuravageLinkInterface.value && !_.isUndefined(link.connectedLinkId);
      });
      return _.sortBy(splitLinks[0], [
        function (s) {
          return (_.isUndefined(s.points) || _.isUndefined(s.points[0])) ? Infinity : s.points[0].y;},
        function (s) {
          return (_.isUndefined(s.points) || _.isUndefined(s.points[0])) ? Infinity : s.points[0].x;}]);
    };

    var openSplit = function (linkid, multiSelect) {
      if (!multiSelect) {
        current = projectLinkCollection.getByLinkId([linkid]);
        ids = [linkid];
      } else {
        ids = projectLinkCollection.getMultiSelectIds(linkid);
        current = projectLinkCollection.getByLinkId(ids);
      }
      var orderedSplitParts = orderSplitParts(get());
      var suravageA = orderedSplitParts[0];
      var suravageB = orderedSplitParts[1];
      suravageA.marker = "A";
      if (!suravageB){
        suravageB = zeroLengthSplit(suravageA);
        suravageA.points = suravageA.originalGeometry;
      }
      suravageB.marker = "B";
      eventbus.trigger('split:projectLinks', [suravageA, suravageB]);
    };

    var preSplitSuravageLink = function(suravage, nearestPoint) {
      projectLinkCollection.preSplitProjectLinks(suravage.linkId, nearestPoint);
        eventbus.once('projectLink:preSplitSuccess', function(data){
          preSplitData = data;
          var suravageA = data.a;
          var suravageB = data.b;
          var terminatedC = data.c;
          ids = projectLinkCollection.getMultiSelectIds(suravageA.linkId);
          current = projectLinkCollection.getByLinkId(_.flatten(ids));
          suravageA.marker = "A";
          suravageB.marker = "B";
          terminatedC.marker = "C";
          suravageA.text = "SUUNNITELMALINKKI";
          suravageB.text = "SUUNNITELMALINKKI";
          terminatedC.text = "NYKYLINKKI";
          suravageA.splitPoint = nearestPoint;
          suravageB.splitPoint = nearestPoint;
          terminatedC.splitPoint = nearestPoint;
          applicationModel.removeSpinner();
          eventbus.trigger('split:projectLinks', [suravageA, suravageB, terminatedC]);
          eventbus.trigger('split:cutPointFeature', data.split);
        });
    };

    var splitSuravageLinks = function(nearestSuravage, split, mousePoint, callback) {
      var left = _.cloneDeep(nearestSuravage);
      left.points = split.firstSplitVertices;

      var right = _.cloneDeep(nearestSuravage);
      right.points = split.secondSplitVertices;
      var measureLeft = calculateMeasure(left);
      var measureRight = calculateMeasure(right);
      splitSuravage.created = left;
      splitSuravage.created.endMValue = measureLeft;
      splitSuravage.existing = right;
      splitSuravage.existing.endMValue = measureRight;
      splitSuravage.created.splitPoint = mousePoint;
      splitSuravage.existing.splitPoint = mousePoint;

      splitSuravage.created.id = null;
      splitSuravage.splitMeasure = split.splitMeasure;

      splitSuravage.created.marker = 'A';
      splitSuravage.existing.marker = 'B';

      callback(splitSuravage);
    };

    var getPoint = function(link) {
      if (link.sideCode == LinkValues.SideCode.AgainstDigitizing.value) {
        return _.first(link.points);
      } else {
        return _.last(link.points);
      }
    };

    var calculateMeasure = function(link) {
      var points = _.map(link.points, function(point) {
        return [point.x, point.y];
      });
      return new ol.geom.LineString(points).getLength();
    };

    var isDirty = function() {
      return dirty;
    };

    var setDirty = function(value) {
      dirty = value;
    };

    var openShift = function(linkIds) {
      if (linkIds.length === 0) {
        cleanIds();
        close();
      } else {
        var added = _.difference(linkIds, ids);
        ids = linkIds;
        current = _.filter(current, function(link) {
          return _.contains(linkIds, link.getData().linkId);
          }
        );
        current = current.concat(projectLinkCollection.getByLinkId(added));
        eventbus.trigger('projectLink:clicked', get());
      }
    };

    var get = function(linkId) {
      var clicked = _.filter(current, function (c) {return c.getData().linkId == linkId;});
      var others = _.filter(_.map(current, function(projectLink) { return projectLink.getData();}), function (link) {
        return link.linkId != linkId;
      });
      if (!_.isUndefined(clicked[0])){
        return [clicked[0].getData()].concat(others);
      }
      return others;
    };

    var getPreSplitData = function(){
      return preSplitData;
    };

    var setCurrent = function(newSelection) {
      current = newSelection;
    };
    var isSelected = function(linkId) {
      return _.contains(ids, linkId);
    };

    var clean = function(){
      current = [];
    };

    var cleanIds = function(){
      ids = [];
    };

    var close = function(){
      current = [];
      eventbus.trigger('layer:enableButtons', true);
    };

    var revertSuravage = function(){
      splitSuravage = {};
    };

    return {
      open: open,
      openShift: openShift,
      openSplit: openSplit,
      get: get,
      clean: clean,
      cleanIds: cleanIds,
      close: close,
      isSelected: isSelected,
      setCurrent: setCurrent,
      isDirty: isDirty,
      setDirty: setDirty,
      // splitSuravageLink: splitSuravageLink,
      preSplitSuravageLink: preSplitSuravageLink,
      getPreSplitData: getPreSplitData,
      revertSuravage: revertSuravage
    };
  };
})(this);
