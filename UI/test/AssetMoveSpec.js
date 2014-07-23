/*jshint expr: true*/
define(['chai', 'eventbus', 'TestHelpers', 'AssetsTestData'], function(chai, eventbus, testHelpers, assetsTestData) {
  var expect = chai.expect;
  var assetsData = assetsTestData.withValidityPeriods(['current', 'current']);
  var assetData = _.merge({}, assetsData[0], {propertyData: []});

  describe('when loading application with two bus stops', function() {
    var openLayersMap;
    before(function(done) {
      var backend = testHelpers.fakeBackend(assetsData, assetData);
      testHelpers.restartApplication(function(map) {
        openLayersMap = map;
        done();
      }, backend);
    });
    describe('and moving bus stop', function() {
      var originalYPosition;
      var testAssetId = 300348;
      before(function(done) {
        var marker = _.find(testHelpers.getAssetMarkers(openLayersMap), {id: testAssetId});
        originalYPosition = marker.bounds.top;
        $('.edit-mode-btn').click();
        testHelpers.clickMarker(testAssetId, openLayersMap);
        testHelpers.moveMarker(testAssetId, openLayersMap, 1, 0);
        done();
      });
      it('moves bus stop', function() {
        var marker = _.find(testHelpers.getAssetMarkers(openLayersMap), {id: testAssetId});
        expect(marker.bounds.top).to.be.above(originalYPosition);
      });
      describe('and canceling bus stop move', function() {
        before(function() { $('button.cancel').click(); });

        it('returns bus stop to original location', function() {
          var marker = _.find(testHelpers.getAssetMarkers(openLayersMap), {id: testAssetId});
          expect(marker.bounds.top).to.equal(originalYPosition);
        });
      });
    });
  });
});
