(function (root) {
  root.TotalWeightLimitForm = function(selectedTotalWeightLimit, newWeightLimitTitle, className) {
    var template = function(selectedTotalWeightLimit) {
      var modifiedBy = selectedTotalWeightLimit.getModifiedBy() || '-';
      var modifiedDateTime = selectedTotalWeightLimit.getModifiedDateTime() ? ' ' + selectedTotalWeightLimit.getModifiedDateTime() : '';
      var createdBy = selectedTotalWeightLimit.getCreatedBy() || '-';
      var createdDateTime = selectedTotalWeightLimit.getCreatedDateTime() ? ' ' + selectedTotalWeightLimit.getCreatedDateTime() : '';
      var disabled = selectedTotalWeightLimit.isDirty() ? '' : 'disabled';
      var buttons = ['<button class="save btn btn-primary" ' + disabled + '>Tallenna</button>',
          '<button class="cancel btn btn-secondary" ' + disabled + '>Peruuta</button>'].join('');
      var expiredChecked = selectedTotalWeightLimit.expired() ? 'checked' : '';
      var nonExpiredChecked = selectedTotalWeightLimit.expired() ? '' : 'checked';
      var value = selectedTotalWeightLimit.getValue() ? selectedTotalWeightLimit.getValue() + ' kg' : '-';
      var title = selectedTotalWeightLimit.isNew() ?
        '<span>' + newWeightLimitTitle + '</span>' :
        '<span>Segmentin ID: ' + selectedTotalWeightLimit.getId() + '</span>';
      var header = '<header>' + title + '<div class="' + className + ' form-controls">' + buttons + '</div></header>';
      return header +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div class="form-group">' +
        '<p class="form-control-static asset-log-info">Lis&auml;tty j&auml;rjestelm&auml;&auml;n: ' + createdBy + createdDateTime + '</p>' +
        '</div>' +
        '<div class="form-group">' +
        '<p class="form-control-static asset-log-info">Muokattu viimeksi: ' + modifiedBy + modifiedDateTime + '</p>' +
        '</div>' +
        '<div class="form-group editable">' +
        '<label class="control-label">Rajoitus</label>' +
        '<p class="form-control-static ' + className + '">' + value + '</p>' +
        '<div class="choice-group">' +
        '<div class="radio">' +
        '<label>Ei painorajoitusta<input type="radio" name="' + className + '" value="disabled" ' + expiredChecked + '/></label>' +
        '</div>' +
        '<div class="radio">' +
        '<label>Painorajoitus<input type="radio" name="' + className + '" value="enabled" ' + nonExpiredChecked + '/></label>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<div class="form-group editable">' +
        '<label class="control-label"></label>' +
        '<div class="input-group" style="display: none">' +
        '<input type="text" class="form-control ' + className + '">' +
        '<span class="input-group-addon">kg</span>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<footer class="' + className + ' form-controls" style="display: none">' +
        buttons +
        '</footer>';
    };

    var removeWhitespace = function(s) {
      return s.replace(/\s/g, '');
    };

    var setupTotalWeightLimitInput = function(toggleElement, inputElement, selectedTotalWeightLimit) {
      inputElement.val(selectedTotalWeightLimit.getValue());
      inputElement.prop('disabled', selectedTotalWeightLimit.expired());
      inputElement.on('input', function(event) {
        var value = parseInt(removeWhitespace($(event.currentTarget).val()), 10);
        selectedTotalWeightLimit.setValue(value);
      });
      toggleElement.change(function(event) {
        var expired = $(event.currentTarget).val();
        var disabled = expired === 'disabled';
        selectedTotalWeightLimit.setExpired(disabled);
        inputElement.prop('disabled', disabled);
      });
    };

    var bindEvents = function(selectedTotalWeightLimit, className) {
      var rootElement = $('#feature-attributes');
      var toggleMode = function(readOnly) {
        rootElement.find('.editable .form-control-static').toggle(readOnly);
        rootElement.find('.editable .input-group').toggle(!readOnly);
        rootElement.find('.editable .choice-group').toggle(!readOnly);
        rootElement.find('.form-controls').toggle(!readOnly);
      };
      eventbus.on('totalWeightLimit:selected totalWeightLimit:cancelled totalWeightLimit:saved', function() {
        rootElement.html(template(selectedTotalWeightLimit));
        var toggleElement = rootElement.find(".radio input");
        var inputElement = rootElement.find('.' + className);
        setupTotalWeightLimitInput(toggleElement, inputElement, selectedTotalWeightLimit);
        toggleMode(applicationModel.isReadOnly());
      });
      eventbus.on('totalWeightLimit:unselected', function() {
        rootElement.empty();
      });
      eventbus.on('application:readOnly', toggleMode);
      eventbus.on('totalWeightLimit:limitChanged totalWeightLimit:expirationChanged', function() {
        rootElement.find('.form-controls button').attr('disabled', false);
      });
      rootElement.on('click', '.' + className + ' button.save', function() {
        selectedTotalWeightLimit.save();
      });
      rootElement.on('click', '.' + className + ' button.cancel', function() {
        selectedTotalWeightLimit.cancel();
      });
    };

    bindEvents(selectedTotalWeightLimit, className);
  };
})(this);
