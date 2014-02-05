describe('FeatureAttributes', function () {
    describe('when backend returns undefined date', function () {
        var featureAttributes = Oskari.clazz.create('Oskari.digiroad2.bundle.featureattributes.FeatureAttributesBundleInstance');
        featureAttributes.init({});

        it('should construct date attribute with empty content', function () {
            var actualHtml = featureAttributes._makeContent([
                {
                    propertyId: 'propertyId',
                    propertyName: 'propertyName',
                    propertyType: 'date',
                    values: [
                        {imageId: null, propertyDisplayValue: null, propertyValue: 0}
                    ]
                }
            ]);
            assert.equal(actualHtml,
                '<div class="formAttributeContentRow">' +
                    '<div class="formLabels">propertyName</div>' +
                    '<div class="formAttributeContent">' +
                    '<input class="featureAttributeDate" type="text" data-propertyId="propertyId" name="propertyName" value=""/>' +
                    '<span class="attributeFormat">pp.kk.vvvv</span>' +
                    '</div>' +
                    '</div>');
        });
    });

    describe('when user leaves date undefined', function () {
        var featureAttributes = null;
        var calls = [];

        before(function () {
            featureAttributes = Oskari.clazz.create('Oskari.digiroad2.bundle.featureattributes.FeatureAttributesBundleInstance', {
                backend: _.extend({}, window.Backend, {
                    putAssetPropertyValue: function (assetId, propertyId, data) {
                        calls.push(data);
                    },
                    getAsset: function (id, success) {
                        success({
                            propertyData: [createNullDateProperty('propertyId', 'propertyName')]
                        });
                    }
                })
            });
            featureAttributes.init();
        });

        it('should send null date to backend', function () {
            calls = [];
            featureAttributes.showAttributes(130, { x: 24, y: 60, heading: 140 });
            var dateInput = $('input[data-propertyid="propertyId"]');
            dateInput.blur();
            assert.equal(1, calls.length);
            assert.deepEqual(calls[0], []);
        });

        function createNullDateProperty(propertyId, propertyName) {
            return {
                propertyId: propertyId,
                propertyName: propertyName,
                propertyType: 'date',
                values: [
                    {
                        imageId: null,
                        propertyDisplayValue: null,
                        propertyValue: 0
                    }
                ]};
        }
    });

    describe('when feature attribute collection is requested', function () {
        var featureAttributes = null;
        var requestedAssetTypes = [];
        var collectedAttributes = {};

        before(function () {
            requestedAssetTypes = [];
            featureAttributes = Oskari.clazz.create('Oskari.digiroad2.bundle.featureattributes.FeatureAttributesBundleInstance', {
                backend: _.extend({}, window.Backend, {
                    getAssetTypeProperties: function (assetType, success) {
                        requestedAssetTypes.push(assetType);
                        var properties = [
                            { propertyId: '5', propertyName: 'Esteettömyystiedot', propertyType: 'text', required: false, values: [] },
                            { propertyId: '1', propertyName: 'Pysäkin katos', propertyType: 'single_choice', required: true, values: [] }
                            /*
                             { propertyId: '6', propertyName: 'Ylläpitäjän tunnus', propertyType: 'text', required: false, values: [] },
                             { propertyId: '2', propertyName: 'Pysäkin tyyppi', propertyType: 'multiple_choice', required: true, values: [] },
                             { propertyId: '3', propertyName: 'Ylläpitäjä', propertyType: 'single_choice', required: true, values: [] },
                             { propertyId: '4', propertyName: 'Pysäkin saavutettavuus', propertyType: 'text', required: false, values: [] },
                             { propertyId: 'validityDirection', propertyName: 'Vaikutussuunta', propertyType: 'single_choice', required: false, values: [] },
                             { propertyId: 'validFrom', propertyName: 'Käytössä alkaen', propertyType: 'date', required: false, values: [] },
                             { propertyId: 'validTo', propertyName: 'Käytössä päättyen', propertyType: 'date', required: false, values: [] }
                             */
                        ];
                        success(properties);
                    },
                    getEnumeratedPropertyValues: function(assetTypeId, success) {
                        success([
                            /*
                             {"propertyId": "2", "propertyName": "Pysäkin tyyppi", "propertyType": "multiple_choice", "required": true, "values": [
                             {"propertyValue": 1, "propertyDisplayValue": "Raitiovaunu", "imageId": null},
                             {"propertyValue": 2, "propertyDisplayValue": "Linja-autojen paikallisliikenne", "imageId": null},
                             {"propertyValue": 3, "propertyDisplayValue": "Linja-autojen kaukoliikenne", "imageId": null},
                             {"propertyValue": 4, "propertyDisplayValue": "Linja-autojen pikavuoro", "imageId": null},
                             {"propertyValue": 99, "propertyDisplayValue": "Ei tietoa", "imageId": null}
                             ]},
                             */
                            {propertyId: "1", propertyName: "Pysäkin katos", propertyType: "single_choice", required: true, values: [
                                {propertyValue: 1, propertyDisplayValue: "Ei", imageId: null},
                                {propertyValue: 2, propertyDisplayValue: "Kyllä", imageId: null},
                                {propertyValue: 99, propertyDisplayValue: "Ei tietoa", imageId: null}
                            ]}
                        ]);
                    }
                })
            });
            featureAttributes.init();
            featureAttributes.collectAttributes(function(attributeCollection) { collectedAttributes = attributeCollection; });
        });

        it('should call backend for bus stop properties', function () {
            assert.equal(1, requestedAssetTypes.length);
            assert.equal(10, requestedAssetTypes[0]);
        });

        it('should create text field for property "Esteettömyystiedot"', function() {
            var textProperty = $('input[data-propertyid="5"]');
            assert.equal(1, textProperty.length);
            assert.equal(true, textProperty.hasClass('featureAttributeText'));
            assert.equal('Esteettömyystiedot', textProperty.attr('name'));
        });

        it('should create single choice field for property "Pysäkin katos"', function() {
            var singleChoiceElement = $('select[data-propertyid="1"]');
            assert.equal(1, singleChoiceElement.length);
            assert.equal(true, singleChoiceElement.hasClass('featureattributeChoice'));
            assert.isUndefined(singleChoiceElement.attr('multiple'));
        });

        it('should call callback with attribute collection when save is clicked', function() {
            var saveButton = $('button.save');
            var textProperty = $('input[data-propertyid="5"]');
            textProperty.val('textValue');
            saveButton.click();
            assert.equal(1, collectedAttributes.length);
            assert.deepEqual(collectedAttributes[0], { propertyId: '5', propertyValues: [ { propertyValue:0, propertyDisplayValue:'textValue' } ] });
        });
    });
});
