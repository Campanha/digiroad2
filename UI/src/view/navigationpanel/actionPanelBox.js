(function(root) {
  root.ActionPanelBox = function() {
    var me = this;
    var authorizationPolicy = new AuthorizationPolicy();

    this.selectToolIcon = '<img src="images/select-tool.svg"/>';
    this.cutToolIcon = '<img src="images/cut-tool.svg"/>';
    this.addToolIcon = '<img src="images/add-tool.svg"/>';
    this.rectangleToolIcon = '<img src="images/rectangle-tool.svg"/>';
    this.polygonToolIcon = '<img src="images/polygon-tool.svg"/>';
    this.terminalToolIcon = '<img src="images/add-terminal-tool.svg"/>';
    this.checkIcon = '<img src="images/check-icon.png" title="Kuntakäyttäjän todentama"/>';

    this.Tool = function (toolName, icon) {
      var className = toolName.toLowerCase();
      var element = $('<div class="action"/>').addClass(className).attr('action', toolName).append(icon).click(function () {
        me.executeOrShowConfirmDialog(function () {
          applicationModel.setSelectedTool(toolName);
        });
      });
      var deactivate = function () {
        element.removeClass('active');
      };
      var activate = function () {
        element.addClass('active');
      };

      return {
        element: element,
        deactivate: deactivate,
        activate: activate,
        name: toolName
      };
    };
    this.ToolSelection = function (tools) {
      var element = $('<div class="panel-section panel-actions" />');
      _.each(tools, function (tool) {
        element.append(tool.element);
      });
      var hide = function () {
        element.hide();
      };
      var show = function () {
        element.show();
      };
      var deactivateAll = function () {
        _.each(tools, function (tool) {
          tool.deactivate();
        });
      };
      var reset = function () {
        deactivateAll();
        tools[0].activate();
      };
      eventbus.on('tool:changed', function (name) {
        _.each(tools, function (tool) {
          if (tool.name != name) {
            tool.deactivate();
          } else {
            tool.activate();
          }
        });
      });

      hide();

      return {
        element: element,
        reset: reset,
        show: show,
        hide: hide
      };
    };

    this.executeOrShowConfirmDialog = function(f) {
      if (applicationModel.isDirty()) {
        new Confirm();
      } else {
        f();
      }
    };

    this.template = function () {};
    this.header = function () {};
    this.title = undefined;
    this.layerName = undefined;
    this.labeling = function () {};
    this.checkboxPanel = function () {};
    this.predicate = function () {};
    this.radioButton = function () {};
    this.legendName = function () {};
    this.municipalityVerified = function () {};

    this.predicate = function () {
      return authorizationPolicy.editModeAccess();
    };

    this.elements = function (){
      return { expanded: $([
        me.panel(),
        me.radioButton(),
        me.labeling(),
        me.checkboxPanel(),
        me.bindExternalEventHandlers(),
        '  </div>',
        '</div>'].join(''))  };
    };

    this.panel = function () {
      return [ '<div class="panel ' + me.layerName +'">',
               '  <header class="panel-header expanded">',
                me.header() ,
               '  </header>',
               '   <div class="panel-section panel-legend '+ me.legendName() + '-legend">'].join('');
    };

    this.verificationIcon = function() {
      return '<div id="right-panel">' + me.checkIcon + '</div>';
    };

    this.addVerificationIcon = function(){
      $(me.expanded).find('.panel-header').css('display', 'flex');
      $(me.expanded).find('.panel-header').append(me.verificationIcon());
    };

    this.bindExternalEventHandlers = function() {

      eventbus.on('roles:fetched', function() {
        if (me.predicate()) {
          me.toolSelection.reset();
          $(me.expanded).append(me.toolSelection.element);
          $(me.expanded).append(me.editModeToggle.element);
        }
        if(me.municipalityVerified()){
          me.addVerificationIcon();
        }
      });

      eventbus.on('verificationInfo:fetched', function(visible) {
        var img = me.expanded.find('#right-panel');
        if (visible)
          img.css('display','inline');
        else
          img.css('display','none');
      });

      eventbus.on('application:readOnly', function(readOnly) {
        $(me.expanded).find('.panel-header').toggleClass('edit', !readOnly);
      });
    };

    this.eventHandler = function(){
      $(me.expanded).find('#complementaryLinkCheckBox').on('change', function (event) {
        if ($(event.currentTarget).prop('checked')) {
          eventbus.trigger(me.layerName + '-complementaryLinks:show');
        } else {
          if (applicationModel.isDirty()) {
            $(event.currentTarget).prop('checked', true);
            new Confirm();
          } else {
            eventbus.trigger(me.layerName +'-complementaryLinks:hide');
          }
        }
      });

      $(me.expanded).find('#trafficSignsCheckbox').on('change', function (event) {
        if ($(event.currentTarget).prop('checked')) {
          eventbus.trigger(me.layerName + '-readOnlyTrafficSigns:show');
        } else {
          eventbus.trigger(me.layerName + '-readOnlyTrafficSigns:hide');
        }
      });
    };
  };
})(this);
