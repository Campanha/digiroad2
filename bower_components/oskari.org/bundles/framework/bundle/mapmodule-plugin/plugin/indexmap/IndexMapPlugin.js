/**
 * @class Oskari.mapframework.bundle.mapmodule.plugin.IndexMapPlugin
 *
 * Provides indexmap functionality for map. Uses image from plugin resources as the index map.
 *
 * See http://www.oskari.org/trac/wiki/DocumentationBundleMapModulePluginIndexMap
 */
Oskari.clazz.define('Oskari.mapframework.bundle.mapmodule.plugin.IndexMapPlugin',
    /**
     * @method create called automatically on construction
     * @static
     * @param {Object} config
     *      JSON config with params needed to run the plugin
     */

    function (config) {
        var me = this;
        me.mapModule = null;
        me.pluginName = null;
        me._sandbox = null;
        me._map = null;
        me.conf = config;
        me.element = null;
        me._indexMap = null;
        me._indexMapUrl = '/framework/bundle/mapmodule-plugin/plugin/indexmap/images/suomi25m_tm35fin.png';
    }, {
        templates: {
            main: jQuery('<div class="mapplugin indexmap"></div>'),
            toggle: jQuery('<div class="indexmapToggle"></div>')
        },

        /** @static @property __name plugin name */
        __name: 'IndexMapPlugin',

        /**
         * @method getName
         * @return {String} plugin name
         */
        getName: function () {
            return this.pluginName;
        },
        /**
         * @method getMapModule
         * @return {Oskari.mapframework.ui.module.common.MapModule} reference to map
         * module
         */
        getMapModule: function () {
            return this.mapModule;
        },
        /**
         * @method setMapModule
         * @param {Oskari.mapframework.ui.module.common.MapModule} reference to map
         * module
         */
        setMapModule: function (mapModule) {
            this.mapModule = mapModule;
            if (mapModule) {
                this.pluginName = mapModule.getName() + this.__name;
            }
        },
        /**
         * @method hasUI
         * This plugin has an UI so always returns true
         * @return {Boolean} true
         */
        hasUI: function () {
            return true;
        },
        /**
         * @method init
         * Interface method for the module protocol
         *
         * @param {Oskari.mapframework.sandbox.Sandbox} sandbox
         *          reference to application sandbox
         */
        init: function (sandbox) {

        },

        /**
         * Sets the location of the indexmap.
         *
         * @method setLocation
         * @param {String} location The new location
         */
        setLocation: function (location) {
            var me = this;
            if (!me.conf) {
                me.conf = {};
            }
            me.conf.location = location;

            // reset plugin if active
            if (me.element) {
                me.stopPlugin();
                me.startPlugin();
            }
        },
        /**
         * @method _createUI
         * @private
         *
         * Constructs/initializes the indexmap  control for the map.
         */
        _createUI: function () {
            /* overview map */
            var me = this,
                graphic = new OpenLayers.Layer.Image('Overview map image',
                    me.getMapModule().getImageUrl() + me._indexMapUrl,
                    new OpenLayers.Bounds(26783, 6608595, 852783, 7787250), new OpenLayers.Size(120, 173)),
                containerClasses = 'bottom right',
                position = 5;

            me.element = me.templates.main.clone();

            /*
             * create an overview map control with non-default
             * options
             */
            var controlOptions = {
                div: me.element[0],
                mapOptions: {
                    maxExtent: new OpenLayers.Bounds(26783, 6608595, 852783, 7787250),
                    units: 'm',
                    projection: me._map.getProjection(),
                    numZoomLevels: 1
                },
                layers: [graphic],
                size: new OpenLayers.Size(120, 173),
                autoPan: false,
                controls: []
            };

            /* Indexmap */
            if (me.conf && me.conf.location) {
                containerClasses = me.conf.location.classes || containerClasses;
                position = me.conf.location.position || position;
            }

            // add container to map
            me.getMapModule().setMapControlPlugin(me.element, containerClasses, position);
            // initialize control, pass container
            me._indexMap = new OpenLayers.Control.OverviewMap(controlOptions);


        },
        /**
         * @method register
         * Interface method for the module protocol
         */
        register: function () {

        },
        /**
         * @method unregister
         * Interface method for the module protocol
         */
        unregister: function () {

        },
        /**
         * @method startPlugin
         * Interface method for the plugin protocol.
         * Adds the indexmap to the map as control.
         *
         * @param {Oskari.mapframework.sandbox.Sandbox} sandbox
         *          reference to application sandbox
         */
        startPlugin: function (sandbox) {
            var me = this,
                p;
            me._sandbox = sandbox || me.getMapModule().getSandbox();
            me._map = me.getMapModule().getMap();
            me._createUI();

            me._sandbox.register(me);
            for (p in me.eventHandlers) {
                if (me.eventHandlers.hasOwnProperty(p)) {
                    me._sandbox.registerForEventByName(me, p);
                }
            }
            me.getMapModule().addMapControl('overviewMap', me._indexMap);
            var toggleButton = me.templates.toggle.clone();
            // add toggle functionality to button
            toggleButton.click(function () {
                me.element.find('.olControlOverviewMapElement').toggle();
            });
            // button has to be added separately so the element order is correct...
            me.element.append(toggleButton);
        },
        /**
         * @method stopPlugin
         * Interface method for the plugin protocol.
         * Removes the indexmap from the map controls.
         *
         * @param {Oskari.mapframework.sandbox.Sandbox} sandbox
         *          reference to application sandbox
         */
        stopPlugin: function (sandbox) {
            var me = this,
                p;
            me.getMapModule().removeMapControl('overviewMap');

            for (p in me.eventHandlers) {
                if (me.eventHandlers.hasOwnProperty(p)) {
                    me._sandbox.unregisterFromEventByName(me, p);
                }
            }

            me._sandbox.unregister(me);
            me._map = null;
            me._sandbox = null;
            if (me.element) {
                me.element.remove();
                me.element = undefined;
            }
        },
        /**
         * @method start
         * Interface method for the module protocol
         *
         * @param {Oskari.mapframework.sandbox.Sandbox} sandbox
         *          reference to application sandbox
         */
        start: function (sandbox) {},
        /**
         * @method stop
         * Interface method for the module protocol
         *
         * @param {Oskari.mapframework.sandbox.Sandbox} sandbox
         *          reference to application sandbox
         */
        stop: function (sandbox) {},
        /**
         * @property {Object} eventHandlers
         * @static
         */
        eventHandlers: {
            'AfterMapMoveEvent': function (event) {
                if (this._indexMap) {
                    this._indexMap.update();
                }
            }
        },

        /**
         * @method onEvent
         * @param {Oskari.mapframework.event.Event} event a Oskari event object
         * Event is handled forwarded to correct #eventHandlers if found or discarded
         * if
         * not.
         */
        onEvent: function (event) {
            return this.eventHandlers[event.getName()].apply(this, [event]);
        }
    }, {
        /**
         * @property {String[]} protocol array of superclasses as {String}
         * @static
         */
        'protocol': ["Oskari.mapframework.module.Module", "Oskari.mapframework.ui.module.common.mapmodule.Plugin"]
    });