(function(root) {
  root.GeometryUtils = function() {
    var subtractVector = function(vector1, vector2) {
      return {x: vector1.x - vector2.x, y: vector1.y - vector2.y};
    };

    var scaleVector = function(vector, scalar) {
      return {x: vector.x * scalar, y: vector.y * scalar};
    };
    var sumVectors = function(vector1, vector2) {
      return {x: vector1.x + vector2.x, y: vector1.y + vector2.y};
    };
    var normalVector = function(vector) {
      return {x: vector.y, y: -vector.x};
    };
    var unitVector = function(vector) {
      var n = vectorLength(vector);
      return {x: vector.x / n, y: vector.y / n};
    };

    var vectorLength = function(vector) {
      return Math.sqrt(Math.pow(vector.x, 2) + Math.pow(vector.y, 2));
    };

    var segmentsOfLineString = function(lineString, point) {
      return _.reduce(lineString.getVertices(), function(acc, vertex, index, vertices) {
        if (index > 0) {
          var previousVertex = vertices[index - 1];
          var segmentGeometry = new OpenLayers.Geometry.LineString([previousVertex, vertex]);
          var distanceObject = segmentGeometry.distanceTo(point, { details: true });
          var segment = {
            distance: distanceObject.distance,
            splitPoint: {
              x: distanceObject.x0,
              y: distanceObject.y0
            },
            index: index - 1
          };
          return acc.concat([segment]);
        } else {
          return acc;
        }
      }, []);
    };

    this.calculateMeasureAtPoint = function(lineString, point) {
      var segments = segmentsOfLineString(lineString, point);
      var splitSegment = _.head(_.sortBy(segments, 'distance'));
      var split = _.reduce(lineString.getVertices(), function(acc, vertex, index) {
        if (acc.firstSplit) {
          if (acc.previousVertex) {
            acc.splitMeasure = acc.splitMeasure + vectorLength(subtractVector(acc.previousVertex, vertex));
          }
          if (index === splitSegment.index) {
            acc.splitMeasure = acc.splitMeasure + vectorLength(subtractVector(vertex, splitSegment.splitPoint));
            acc.firstSplit = false;
          }
          acc.previousVertex = vertex;
        }
        return acc;
      }, {
        firstSplit: true,
        previousVertex: null,
        splitMeasure: 0.0
      });
      return split.splitMeasure;
    };

    this.splitByPoint = function(lineString, point) {
      var segments = segmentsOfLineString(lineString, point);
      var splitSegment = _.head(_.sortBy(segments, 'distance'));
      var split = _.reduce(lineString.getVertices(), function(acc, vertex, index) {
        if (acc.firstSplit) {
          acc.firstSplitVertices.push({ x: vertex.x, y: vertex.y });
          if (index === splitSegment.index) {
            acc.firstSplitVertices.push({ x: splitSegment.splitPoint.x, y: splitSegment.splitPoint.y });
            acc.secondSplitVertices.push({ x: splitSegment.splitPoint.x, y: splitSegment.splitPoint.y });
            acc.firstSplit = false;
          }
        } else {
          acc.secondSplitVertices.push({ x: vertex.x, y: vertex.y });
        }
        return acc;
      }, {
        firstSplit: true,
        firstSplitVertices: [],
        secondSplitVertices: []
      });
      return _.pick(split, 'firstSplitVertices', 'secondSplitVertices');
    };

    this.offsetPoint = function(point, index, geometry, sideCode, baseOffset) {
      var previousPoint = index > 0 ? geometry[index - 1] : point;
      var nextPoint = geometry[index + 1] || point;

      var directionVector = scaleVector(sumVectors(subtractVector(point, previousPoint), subtractVector(nextPoint, point)), 0.5);
      var normal = normalVector(directionVector);
      var sideCodeScalar = (2 * sideCode - 5) * baseOffset;
      var offset = scaleVector(unitVector(normal), sideCodeScalar);
      return sumVectors(point, offset);
    };

    var distanceOfPoints = function(end, start) {
      return Math.sqrt(Math.pow(end.x - start.x, 2) + Math.pow(end.y - start.y, 2));
    };

    var radiansToDegrees = function(radians) {
      return radians * (180 / Math.PI);
    };

    var calculateAngleFromNorth = function(vector) {
      var v = unitVector(vector);
      var rad = ((Math.PI * 2) - (Math.atan2(v.y, v.x) + Math.PI)) + (Math.PI / 2);
      var ret = rad > (Math.PI * 2) ? rad - (Math.PI * 2) : rad;
      return radiansToDegrees(ret);
    };

    this.calculateMidpointOfLineString = function(lineString) {
      var length = lineString.getLength();
      var vertices = lineString.getVertices();
      var firstVertex = _.first(vertices);
      var optionalMidpoint = _.reduce(_.tail(vertices), function(acc, vertex) {
        if (acc.midpoint) return acc;
        var distance = distanceOfPoints(vertex, acc.previousVertex);
        var accumulatedDistance = acc.distanceTraversed + distance;
        if (accumulatedDistance < length / 2) {
          return { previousVertex: vertex, distanceTraversed: accumulatedDistance };
        } else {
          return {
            midpoint: {
              x: acc.previousVertex.x + (((vertex.x - acc.previousVertex.x) / distance) * (length / 2 - acc.distanceTraversed)),
              y: acc.previousVertex.y + (((vertex.y - acc.previousVertex.y) / distance) * (length / 2 - acc.distanceTraversed)),
              angleFromNorth: calculateAngleFromNorth(subtractVector(vertex, acc.previousVertex))
            }
          };
        }
      }, {previousVertex: firstVertex, distanceTraversed: 0});
      if (optionalMidpoint.midpoint) return optionalMidpoint.midpoint;
      else return firstVertex;
    };
  };
})(this);

