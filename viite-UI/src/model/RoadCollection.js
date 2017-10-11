(function(root) {
  var RoadLinkModel = function(data) {
    var selected = false;
    var original = _.clone(data);

    var getId = function() {
      return data.roadLinkId || data.linkId;
    };

    var getData = function() {
      return data;
    };

    var getPoints = function() {
      return _.cloneDeep(data.points);
    };

    var setLinkProperty = function(name, value) {
      if (value != data[name]) {
        data[name] = value;
      }
    };

    var select = function() {
      selected = true;
    };

    var unselect = function() {
      selected = false;
    };

    var isSelected = function() {
      return selected;
    };

    var isCarTrafficRoad = function() {
      return !_.isUndefined(data.linkType) && !_.contains([8, 9, 21, 99], data.linkType);
    };

    var cancel = function() {
      data.trafficDirection = original.trafficDirection;
      data.functionalClass = original.functionalClass;
      data.linkType = original.linkType;
    };

    return {
      getId: getId,
      getData: getData,
      getPoints: getPoints,
      setLinkProperty: setLinkProperty,
      isSelected: isSelected,
      isCarTrafficRoad: isCarTrafficRoad,
      select: select,
      unselect: unselect,
      cancel: cancel
    };
  };

  root.RoadCollection = function(backend) {
    var roadLinkGroups = [];
    var roadLinkGroupsSuravage = [];
    var tmpRoadLinkGroups = [];
    var tmpRoadAddresses = [];
    var tmpNewRoadAddresses = [];
    var preMovedRoadAddresses = [];
    var changedIds = [];

    var projectLinkStatus_notHandled = 0;

    var roadLinks = function() {
      return _.flatten(roadLinkGroups);
    };

    var getSelectedRoadLinks = function() {
      return _.filter(roadLinks().concat(suravageRoadLinks()), function(roadLink) {
        return roadLink.isSelected() && roadLink.getData().anomaly === 0;
      });
    };

    this.fetch = function(boundingBox, zoom) {
      backend.getRoadLinks({boundingBox: boundingBox, zoom: zoom}, function(fetchedRoadLinks) {
        var selectedIds = _.map(getSelectedRoadLinks(), function(roadLink) {
          return roadLink.getId();
        });
        var fetchedRoadLinkModels = _.map(fetchedRoadLinks, function(roadLinkGroup) {
          return _.map(roadLinkGroup, function(roadLink) {
            return new RoadLinkModel(roadLink);
          });
        });
        roadLinkGroups = _.reject(fetchedRoadLinkModels, function(roadLinkGroup) {
          return _.some(roadLinkGroup, function(roadLink) {
            _.contains(selectedIds, roadLink.getId());
          });
        }).concat(getSelectedRoadLinks());
        roadLinkGroupsSuravage = _.filter(roadLinkGroups, function(group){
          if(_.isArray(group)){
            return _.some(group, function (roadLink) {
              if (roadLink!==null)
                  return roadLink.getData().roadLinkSource === 3;
                else
                  return false;
            });
          } else {
            return group.getData().roadLinkSource === 3;
          }
        });
        var nonSuravageRoadLinkGroups = _.reject(roadLinkGroups, function(group){
          if(_.isArray(group)){
            return _.some(group, function (roadLink) {
              if (roadLink!==null)
                return roadLink.getData().roadLinkSource === 3;
              else
                return false;
            });
          } else {
            return group.getData().roadLinkSource === 3;
          }
        });
        roadLinkGroups = nonSuravageRoadLinkGroups;
        eventbus.trigger('roadLinks:fetched', nonSuravageRoadLinkGroups);
        if(roadLinkGroupsSuravage.length !== 0)
          eventbus.trigger('suravageRoadLinks:fetched', roadLinkGroupsSuravage);
        if(applicationModel.isProjectButton()){
          eventbus.trigger('linkProperties:highlightSelectedProject', applicationModel.getProjectFeature());
          applicationModel.setProjectButton(false);
        }
      });
    };

    var suravageRoadLinks = function() {
      return _.flatten(roadLinkGroupsSuravage);
    };
    this.getRoadsForMassTransitStops = function() {
      return _.chain(roadLinks())
        .filter(function(roadLink) {
          return roadLink.isCarTrafficRoad() && (roadLink.getData().administrativeClass != "Unknown");
        })
        .map(function(roadLink) {
          return roadLink.getData();
        })
        .value();
    };

    this.getRoadLinkByLinkId = function (linkId) {
      return _.find(_.flatten(roadLinkGroups), function(road) { return road.getId() === linkId; });
    };

    this.getAll = function() {
      return _.map(roadLinks(), function(roadLink) {
        return roadLink.getData();
      });
    };

    this.getSuravageLinks = function() {
      return _.map(_.flatten(roadLinkGroupsSuravage), function(roadLink) {
        return roadLink.getData();
      });
    };

    this.getAllTmp = function(){
      return tmpRoadAddresses;
    };

    this.getTmpRoadLinkGroups = function () {
      return tmpRoadLinkGroups;
    };

    this.get = function(ids) {
      return _.map(ids, function(id) {
        return _.find(roadLinks(), function(road) { return road.getId() === id; });
      });
    };

    this.getByLinkId = function(ids) {
      var segments = _.filter(roadLinks(), function (road){
        return road.getData().linkId == ids;
      });
      return segments;
    };

    this.getById = function(ids) {
      return _.map(ids, function(id) {
        return _.find(roadLinks(), function(road) { return road.getData().id === id; });
      });
    };

    this.getSuravageByLinkId = function(ids) {
      var segments = _.filter(suravageRoadLinks(), function (road){
        return road.getData().linkId == ids;
      });
      return segments;
    };

    this.getSuravageById = function(ids) {
      return _.map(ids, function(id) {
        return _.find(suravageRoadLinks(), function(road) { return road.getData().id === id; });
      });
    };

    this.getGroup = function(id) {
      return _.find(roadLinkGroups, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getId() === id;
        });
      });
    };
    this.getGroupByLinkId = function (linkId) {
      return _.find(roadLinkGroups, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().linkId === linkId;
        });
      });
    };

    this.getSuravageGroupByLinkId = function (linkId) {
      return _.find(roadLinkGroupsSuravage, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().linkId === linkId;
        });
      });
    };


    this.getGroupById = function (id) {
      return _.find(roadLinkGroups, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().id === id;
        });
      });
    };

    this.getSuravageGroupById = function (id) {
      return _.find(roadLinkGroupsSuravage, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().id === id;
        });
      });
    };

    this.setTmpRoadAddresses = function (tmp){
      tmpRoadAddresses = tmp;
    };

    this.addTmpRoadLinkGroups = function (tmp) {
      if(tmpRoadLinkGroups.filter(function (roadTmp) { return roadTmp.getData().linkId === tmp.linkId;}).length === 0) {
        tmpRoadLinkGroups.push(new RoadLinkModel(tmp));
      }
    };

    this.setChangedIds = function (ids){
      changedIds = ids;
    };

    this.getChangedIds = function (){
      return changedIds;
    };

    this.reset = function(){
      roadLinkGroups = [];
    };
    this.resetTmp = function(){
      tmpRoadAddresses = [];
    };
    this.resetChangedIds = function(){
      changedIds = [];
    };

    this.setNewTmpRoadAddresses = function (tmp){
      tmpNewRoadAddresses = tmp;
    };

    this.getNewTmpRoadAddresses = function(){
      return tmpNewRoadAddresses;
    };

    this.resetNewTmpRoadAddresses = function(){
      tmpNewRoadAddresses = [];
    };

    this.addPreMovedRoadAddresses = function(ra){
      preMovedRoadAddresses.push(ra);
    };

    this.getPreMovedRoadAddresses = function(){
      return preMovedRoadAddresses;
    };

    this.resetPreMovedRoadAddresses = function(){
      preMovedRoadAddresses = [];
    };

    var roadIsOther = function(road){
      return  0 === road.roadNumber && 0 === road.anomaly && 0 === road.roadLinkType && 0 === road.roadPartNumber && 99 === road.trackCode;
    };

    var roadIsUnknown = function(road){
      return  0 === road.roadNumber && 1 === road.anomaly && 0 === road.roadLinkType && 0 === road.roadPartNumber && 99 === road.trackCode;
    };
    
    this.findReservedProjectLinks = function(boundingBox, zoomLevel, projectId) {
      backend.getProjectLinks({boundingBox: boundingBox, zoom: zoomLevel, projectId: projectId}, function(fetchedLinks) {
        var notHandledLinks = _.chain(fetchedLinks).flatten().filter(function (link) {
          return link.status === projectLinkStatus_notHandled;
        }).uniq().value();
        var notHandledOL3Features = _.map(notHandledLinks, function(road) {
          var points = _.map(road.points, function (point) {
            return [point.x, point.y];
          });
          var feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
          feature.projectLinkData = road;
          feature.projectId = projectId;
          return feature;
        });
        eventbus.trigger('linkProperties:highlightReservedRoads', notHandledOL3Features);
      });
    };
    
  };
})(this);
