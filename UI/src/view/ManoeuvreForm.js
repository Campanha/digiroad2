(function (root) {
  root.ManoeuvreForm = function(selectedManoeuvreSource) {
    var buttons = '' +
      '<div class="manoeuvres form-controls">' +
        '<button class="save btn btn-primary" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-secondary" disabled>Peruuta</button>' +
      '</div>';
    var template = '' +
      '<header>' +
        '<span>Linkin MML ID: <%= mmlId %></span>' +
        buttons +
      '</header>' +
      '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark form-manoeuvre">' +
          '<div class="form-group">' +
            '<p class="form-control-static asset-log-info">Muokattu viimeksi: <%- modifiedBy %> <%- modifiedAt %> </p>' +
          '</div>' +
          '<label>Kääntyminen kielletty linkeille</label>' +
          '<div></div>' +
        '</div>' +
      '</div>' +
      '<footer>' + buttons + '</footer>';
    var manouvreTemplate = '' +
      '<div class="form-group manoeuvre">' +
        '<p class="form-control-static">MML ID: <%= destMmlId %></p>' +
        '<% if(localizedExceptions.length > 0) { %>' +
        '<div class="form-group">' +
          '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +
          '<ul>' +
            '<% _.forEach(localizedExceptions, function(e) { %> <li><%- e.title %></li> <% }) %>' +
          '</ul>' +
        '</div>' +
        '<% } %>' +
        '<% if(!_.isEmpty(additionalInfo)) { %> <label>Tarkenne: <%- additionalInfo %></label> <% } %>' +
      '</div>';
    var adjacentLinkTemplate = '' +
      '<div class="form-group adjacent-link" manoeuvreId="<%= manoeuvreId %>" mmlId="<%= mmlId %>" style="display: none">' +
        '<div class="form-group">' +
          '<div class="checkbox" >' +
            '<input type="checkbox" <% print(checked ? "checked" : "") %>/>' +
          '</div>' +
          '<p class="form-control-static">MML ID <%= mmlId %> <span class="marker"><%= marker %></span></p>' +
        '</div>' +
        '<div class="exception-group <% print(checked ? "" : "exception-hidden") %>">' +
          '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +

          '<% _.forEach(localizedExceptions, function(selectedException) { %>' +
            '<div class="form-group exception">' +
              '<%= deleteButtonTemplate %>' +
              '<select class="form-control select">' +
                '<% _.forEach(exceptionOptions, function(exception) { %> ' +
                  '<option value="<%- exception.typeId %>" <% if(selectedException.typeId === exception.typeId) { print(selected="selected")} %> ><%- exception.title %></option> ' +
                '<% }) %>' +
              '</select>' +
            '</div>' +
          '<% }) %>' +
          '<%= newExceptionSelect %>' +
          '<div class="form-group">' +
            '<input type="text" class="form-control additional-info" ' +
                               'placeholder="Muu tarkenne, esim. aika." <% print(checked ? "" : "disabled") %> ' +
                               '<% if(additionalInfo) { %> value="<%- additionalInfo %>" <% } %>/>' +
          '</div>' +
        '<div>' +
      '</div>';
    var newExceptionTemplate = '' +
      '<div class="form-group exception">' +
        '<select class="form-control select new-exception" <% print(checked ? "" : "disabled") %> >' +
          '<option class="empty" disabled selected>Valitse tyyppi</option>' +
          '<% _.forEach(exceptionOptions, function(exception) { %> <option value="<%- exception.typeId %>"><%- exception.title %></option> <% }) %>' +
        '</select>' +
      '</div>';
    var deleteButtonTemplate = '<button class="btn-delete delete">x</button>';

    var exceptions = [
      {typeId: 7,  title: 'Henkilöauto'},
      {typeId: 4,  title: 'Kuorma-auto'},
      {typeId: 5,  title: 'Linja-auto'},
      {typeId: 6,  title: 'Pakettiauto'},
      {typeId: 8,  title: 'Taksi'},
      {typeId: 13, title: 'Ajoneuvoyhdistelmä'},
      {typeId: 14, title: 'Traktori tai maatalousajoneuvo'},
      {typeId: 15, title: 'Matkailuajoneuvo'},
      {typeId: 16, title: 'Jakeluauto'},
      {typeId: 18, title: 'Kimppakyytiajoneuvo'},
      {typeId: 19, title: 'Sotilasajoneuvo'},
      {typeId: 20, title: 'Vaarallista lastia kuljettava ajoneuvo'},
      {typeId: 21, title: 'Huoltoajo'},
      {typeId: 22, title: 'Tontille ajo'}
    ];
    var localizeException = function(typeId) {
      return _.find(exceptions, {typeId: typeId});
    };
    var bindEvents = function() {
      var rootElement = $('#feature-attributes');

      function toggleMode(readOnly) {
        rootElement.find('.adjacent-link').toggle(!readOnly);
        rootElement.find('.manoeuvre').toggle(readOnly);
        rootElement.find('.form-controls').toggle(!readOnly);
        if(readOnly){
          rootElement.find('.wrapper').addClass('read-only');
        } else {
          rootElement.find('.wrapper').removeClass('read-only');
        }
      }
      eventbus.on('application:readOnly', toggleMode);

      eventbus.on('manoeuvres:selected manoeuvres:cancelled', function(roadLink) {
        roadLink.modifiedBy = roadLink.modifiedBy || '-';
        roadLink.modifiedAt = roadLink.modifiedAt || '';
        rootElement.html(_.template(template)(roadLink));
        _.each(roadLink.manoeuvres, function(manoeuvre) {
          var attributes = _.merge({}, manoeuvre, {
            localizedExceptions: _.map(manoeuvre.exceptions, localizeException)
          });
          rootElement.find('.form').append(_.template(manouvreTemplate)(attributes));
        });
        _.each(roadLink.adjacent, function(adjacentLink) {
          var manoeuvre = _.find(roadLink.manoeuvres, function(manoeuvre) { return adjacentLink.mmlId === manoeuvre.destMmlId; });
          var checked = manoeuvre ? true : false;
          var manoeuvreId = manoeuvre ? manoeuvre.id.toString(10) : "";
          var localizedExceptions = manoeuvre ? _.map(manoeuvre.exceptions, localizeException) : [];
          var additionalInfo = (manoeuvre && !_.isEmpty(manoeuvre.additionalInfo)) ? manoeuvre.additionalInfo : null;
          var attributes = _.merge({}, adjacentLink, {
            checked: checked,
            manoeuvreId: manoeuvreId,
            exceptionOptions: exceptions,
            localizedExceptions: localizedExceptions,
            additionalInfo: additionalInfo,
            newExceptionSelect: _.template(newExceptionTemplate)({ exceptionOptions: exceptions, checked: checked }),
            deleteButtonTemplate: deleteButtonTemplate
          });

          rootElement.find('.form').append(_.template(adjacentLinkTemplate)(attributes));
        });

        toggleMode(applicationModel.isReadOnly());

        var manoeuvreData = function(formGroupElement) {
          var destMmlId = parseInt(formGroupElement.attr('mmlId'), 10);
          var manoeuvreId = !_.isEmpty(formGroupElement.attr('manoeuvreId')) ? parseInt(formGroupElement.attr('manoeuvreId'), 10) : null;
          var additionalInfo = !_.isEmpty(formGroupElement.find('.additional-info').val()) ? formGroupElement.find('.additional-info').val() : null;
          return {
            manoeuvreId: manoeuvreId,
            destMmlId: destMmlId,
            exceptions: manoeuvreExceptions(formGroupElement),
            additionalInfo: additionalInfo
          };
        };

        var manoeuvreExceptions = function(formGroupElement) {
          var selectedOptions = formGroupElement.find('select option:selected');
          return _.chain(selectedOptions)
            .map(function(option) { return parseInt($(option).val(), 10); })
            .reject(function(val) { return _.isNaN(val); })
            .value();
        };

        var throttledAdditionalInfoHandler = _.throttle(function(event) {
          var manoeuvre = manoeuvreData($(event.delegateTarget));
          var manoeuvreId = manoeuvre.manoeuvreId;
          if (_.isNull(manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setAdditionalInfo(manoeuvreId, manoeuvre.additionalInfo || "");
          }
        }, 1000);
        rootElement.find('.adjacent-link').on('input', 'input[type="text"]', throttledAdditionalInfoHandler);
        rootElement.find('.adjacent-link').on('change', 'input[type="checkbox"]', function(event) {
          var eventTarget = $(event.currentTarget);
          var manoeuvre = manoeuvreData($(event.delegateTarget));
          if (eventTarget.attr('checked') === 'checked') {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.removeManoeuvre(manoeuvre);
          }
        });
        rootElement.find('.adjacent-link').on('change', '.exception .select', function(event) {
          var manoeuvre = manoeuvreData($(event.delegateTarget));
          var manoeuvreId = manoeuvre.manoeuvreId;
          if (_.isNull(manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setExceptions(manoeuvreId, manoeuvre.exceptions);
          }
        });
        rootElement.find('.adjacent-link').on('change', '.new-exception', function(event) {
          var selectElement = $(event.target);
          var formGroupElement = $(event.delegateTarget);
          selectElement.parent().after(_.template(newExceptionTemplate)({
            exceptionOptions: exceptions,
            checked: true
          }));
          selectElement.removeClass('new-exception');
          selectElement.find('option.empty').remove();
          selectElement.before(deleteButtonTemplate);
          selectElement.parent().on('click', 'button.delete', function(event) {
            deleteException($(event.target).parent(), formGroupElement);
          });
        });
        rootElement.find('.adjacent-link').on('click', '.checkbox :checkbox', function(event) {
          var isChecked = $(event.target).is(':checked');
          var selects = $(event.delegateTarget).find('select');
          var button = $(event.delegateTarget).find('button');
          var text = $(event.delegateTarget).find('input[type="text"]');
          var group = $(event.delegateTarget).find('.exception-group');
          if(isChecked){
            selects.prop('disabled', false);
            button.prop('disabled', false);
            text.prop('disabled', false);
            group.slideDown('fast');
          } else {
            selects.prop('disabled', 'disabled');
            button.prop('disabled', 'disabled');
            text.prop('disabled', 'disabled');
            group.slideUp('fast');
          }
        });
        rootElement.find('.adjacent-link').on('click', '.exception button.delete', function(event) {
          deleteException($(event.target).parent(), $(event.delegateTarget));
        });
        var deleteException = function(exceptionRow, formGroupElement) {
          exceptionRow.remove();
          var manoeuvre = manoeuvreData(formGroupElement);
          if (_.isNull(manoeuvre.manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setExceptions(manoeuvre.manoeuvreId, manoeuvre.exceptions);
          }
        };
      });
      eventbus.on('manoeuvres:unselected', function() {
        rootElement.empty();
      });
      eventbus.on('manoeuvres:saved', function() {
        rootElement.find('.form-controls button').attr('disabled', true);
      });
      eventbus.on('manoeuvre:changed', function() {
        rootElement.find('.form-controls button').attr('disabled', false);
      });
      rootElement.on('click', '.manoeuvres button.save', function() {
        selectedManoeuvreSource.save();
      });
      rootElement.on('click', '.manoeuvres button.cancel', function() {
        selectedManoeuvreSource.cancel();
      });
    };

    bindEvents();
  };
})(this);
