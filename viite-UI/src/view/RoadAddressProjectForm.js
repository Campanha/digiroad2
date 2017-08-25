(function (root) {
  root.RoadAddressProjectForm = function(projectCollection) {
    var currentProject = false;
    var selectedProjectLink = false;
    var activeLayer = false;
    var hasNewRoadParts = false;
    var staticField = function(labelText, dataField) {
      var field;
      field = '<div class="form-group">' +
        '<p class="form-control-static asset-log-info">' + labelText + ' : ' + dataField + '</p>' +
        '</div>';
      return field;
    };
    var actionSelectedField = function() {
      //TODO: cancel and save buttons Viite-374
      var field;
      field = '<div class="form-group action-selected-field" hidden = "true">' +
        '<p class="asset-log-info">' + 'Tarkista tekemäsi muutokset.' + '<br>' + 'Jos muutokset ok, tallenna.' + '</p>' +
        '</div>';
      return field;
    };
    var options =['Valitse'];

    var largeInputField = function (dataField) {
      return '<div class="form-group">' +
        '<label class="control-label">LISÄTIEDOT</label>'+
        '<textarea class="form-control large-input roadAddressProject" id="lisatiedot">'+(dataField === undefined || dataField === null ? "" : dataField )+'</textarea>'+
        '</div>';
    };

    var inputFieldRequired = function(labelText, id, placeholder, value, maxLength) {
      var lengthLimit = '';
      if (maxLength)
        lengthLimit = 'maxlength="' + maxLength + '"';
      return '<div class="form-group input-required">' +
        '<label class="control-label required">' + labelText + '</label>' +
        '<input type="text" class="form-control" id = "'+id+'"'+lengthLimit+' placeholder = "'+placeholder+'" value="'+value+'"/>' +
        '</div>';
    };

    var title = function() {
      return '<span class ="edit-mode-title">Uusi tieosoiteprojekti</span>';
    };

    var titleWithProjectName = function(projectName) {
      return '<span class ="edit-mode-title">'+projectName+'<button id="editProject_'+ currentProject.id +'" ' +
        'class="btn-edit-project" style="visibility:hidden;" value="' + currentProject.id + '"></button></span>' +
        '<span id="closeProject" class="rightSideSpan" style="visibility:hidden;">Sulje Projekti</span>';
    };

    var actionButtons = function(ready) {
      var html = '<div class="project-form form-controls" id="actionButtons">' +
        '<button id="generalNext" class="save btn btn-save" style="width:auto;">Jatka Toimenpiteisiin</button>' +
        '<button class="cancel btn btn-cancel">Poistu</button>' +
        '</div>';
      return html;
    };

    var selectedData = function (selected) {
      var span = '';
      if (selected[0]) {
        var link = selected[0];
        var startM = Math.min.apply(Math, _.map(selected, function(l) { return l.startAddressM; }));
        var endM = Math.max.apply(Math, _.map(selected, function(l) { return l.endAddressM; }));
        span = '<div class="edit-control-group choice-group">' +
          '<label class="control-label-floating"> TIE </label>' +
          '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + link.roadNumber + '</span>' +
          '<label class="control-label-floating"> OSA </label>' +
          '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + link.roadPartNumber + '</span>' +
          '<label class="control-label-floating"> AJR </label>' +
          '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + link.trackCode + '</span>' +
          '<label class="control-label-floating"> M: </label>' +
          '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + startM + ' - ' + endM + '</span>' +
          '</div>' +
          '</div>';
      }
      return span;
    };

    var newProjectTemplate = function() {
      return _.template('' +
        '<header>' +
        title() +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div class="edit-control-group choice-group">' +
        staticField('Lisätty järjestelmään', '-') +
        staticField('Muokattu viimeksi', '-') +
        '<div class="form-group editable form-editable-roadAddressProject"> ' +
        '<form  id="roadAddressProject"  class="input-unit-combination form-group form-horizontal roadAddressProject">' +
        inputFieldRequired('*Nimi', 'nimi', '', '', 32) +
        inputFieldRequired('*Alkupvm', 'alkupvm', 'pp.kk.vvvv', '') +
        largeInputField() +
        '<div class="form-group">' +
        '<label class="control-label"></label>' +
        addSmallLabel('TIE') + addSmallLabel('AOSA') + addSmallLabel('LOSA') +
        '</div>' +
        '<div class="form-group">' +
        '<label class="control-label">Tieosat</label>' +
        addSmallInputNumber('tie') + addSmallInputNumber('aosa') + addSmallInputNumber('losa') +  addReserveButton() +
        '</div>' +
        '</form>' +
        ' </div>'+
        '</div>' + '<div class = "form-result">'  +'<label >' + 'PROJEKTIIN VALITUT TIEOSAT:' + '</label>'+
        '<div style="margin-left: 20px;">' +
        addSmallLabel('TIE')+ addSmallLabel('OSA')+ addSmallLabel('PITUUS')+ addSmallLabel('JATKUU')+ addSmallLabel('ELY')+
        '</div>'+
        '<div id ="roadpartList">'+
        '</div></div>' +
        '</div> </div>'  +
        '<footer>' + actionButtons(false) + '</footer>');
    };

    var openProjectTemplate = function(project, reservedRoads, newReservedRoads) {
      return _.template('' +
        '<header>' +
        titleWithProjectName(project.name) +
        '</header>' +
        '<div class="wrapper read-only">'+
        '<div class="form form-horizontal form-dark">'+
        '<div class="edit-control-group choice-group">'+
        staticField('Lisätty järjestelmään', project.createdBy + ' ' + project.startDate)+
        staticField('Muokattu viimeksi', project.modifiedBy + ' ' + project.dateModified)+
        '<div class="form-group editable form-editable-roadAddressProject"> '+
        '<form id="roadAddressProject" class="input-unit-combination form-group form-horizontal roadAddressProject">'+
        inputFieldRequired('*Nimi', 'nimi', '', project.name, 32) +
        inputFieldRequired('*Alkupvm', 'alkupvm', 'pp.kk.vvvv', project.startDate)+
        largeInputField(project.additionalInfo)+
        '<div class="form-group">' +
        '<label class="control-label"></label>' +
        addSmallLabel('TIE')+ addSmallLabel('AOSA')+ addSmallLabel('LOSA')+
        '</div>'+
        '<div class="form-group">' +
        '<label class="control-label">Tieosat</label>' +
        addSmallInputNumber('tie')+ addSmallInputNumber('aosa')+ addSmallInputNumber('losa')+ addReserveButton() +
        '</div>'+
        '</form>' +
        '</div>'+
        '</div>' +
        '<div class = "form-result">' +
        '<label>PROJEKTIIN VALITUT TIEOSAT:</label>'+
        '<div>'+
        addSmallLabel('TIE')+ addSmallLabel('OSA')+ addSmallLabel('PITUUS')+ addSmallLabel('JATKUU')+ addSmallLabel('ELY')+
        '</div>'+
        '<div id ="reservedRoads">'+
        reservedRoads +
        '</div></div></br></br>' +
        '<div class = "form-result">' +
        '<label>UUDET VARATUT TIEOSAT:</label>'+
        '<div style="margin-left: 20px;">'+
        addSmallLabel('TIE')+ addSmallLabel('OSA')+ addSmallLabel('PITUUS')+ addSmallLabel('JATKUU')+ addSmallLabel('ELY')+
        '</div>'+
        '<div id ="newReservedRoads">'+
        newReservedRoads +
        '</div></div></div></div>'+
        '<footer>' + actionButtons(reservedRoads !== '') + '</footer>');
    };

    var selectedProjectLinkTemplate = function(project, optionTags, selected) {
      var selection = selectedData(selected);

      return _.template('' +
        '<header>' +
        titleWithProjectName(project.name) +
        '</header>' +
        '<footer>'+ showProjectChangeButton() +'</footer>');
    };

    var showProjectChangeButton = function() {
      return '<div class="project-form form-controls">' +
        '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button disabled id ="send-button" class="send btn btn-block btn-send">Tee tieosoitteenmuutosilmoitus</button></div>';
    };

    var addSmallLabel = function(label){
      return '<label class="control-label-small">'+label+'</label>';
    };

    var addSmallInputNumber = function(id, value){
      //Validate only number characters on "onkeypress" including TAB and backspace
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
        '" class="form-control small-input roadAddressProject" id="'+id+'" value="'+(_.isUndefined(value)? '' : value )+'" onclick=""/>';
    };

    var addDatePicker = function () {
      var $validFrom = $('#alkupvm');
      dateutil.addSingleDependentDatePicker($validFrom);
    };

    var formIsInvalid = function(rootElement) {
      return !(rootElement.find('#nimi').val() && rootElement.find('#alkupvm').val() !== '');
    };

    var formIsValid = function(rootElement) {
      if (rootElement.find('#nimi').val() && rootElement.find('#alkupvm').val() !== ''){
        return false;
      }
      else {
        return true;
      }
    };

    var projDateEmpty = function(rootElement) {
      return !rootElement.find('#alkupvm').val();
    };

    var addReserveButton = function() {
      return '<button class="btn btn-reserve" disabled>Lisää</button>';
    };

    var bindEvents = function() {

      var rootElement = $('#feature-attributes');
      var toggleMode = function(readOnly) {
        rootElement.find('.wrapper read-only').toggle();
      };

      var removePart = function (roadNumber, roadPartNumber) {
        projectCollection.setDirtyRoadParts(projectCollection.deleteRoadPartFromList(projectCollection.getDirtyRoadParts(), roadNumber, roadPartNumber));
        projectCollection.setReservedDirtyRoadParts(projectCollection.deleteRoadPartFromList(projectCollection.getReservedDirtyRoadParts(), roadNumber, roadPartNumber));
        fillForm(projectCollection.getDirtyRoadParts(), projectCollection.getReservedDirtyRoadParts());
      };

      var writeHtmlList = function (list) {
        var text = '';
        var index = 0;
        _.each(list, function (line) {
          text += '<div style="display:inline-block;">' + projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber) +
            addSmallLabel(line.roadNumber) +
            addSmallLabel(line.roadPartNumber) + addSmallLabel(line.roadLength) + addSmallLabel(line.discontinuity) + addSmallLabel(line.ely) +
            '</div>';
        });
        return text;
      };

      var saveChanges = function() {
        var data = $('#roadAddressProject').get(0);
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          hasNewRoadParts = false;
          currentProject = result.project;
          currentProject.isDirty = false;
          var text = '';
          var index = 0;
          projectCollection.setCurrentRoadPartList(result.formInfo);
          projectCollection.setReservedDirtyRoadParts([]);
          _.each(result.formInfo, function(line){
            var button = projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber);
            text += '<div style="display:inline-block;">'  + button +
              addSmallLabel(line.roadNumber)+ addSmallLabel(line.roadPartNumber)+ addSmallLabel(line.roadLength)+ addSmallLabel(line.discontinuity)+ addSmallLabel(line.ely) +
              '</div>';
          });
          rootElement.html(openProjectTemplate(currentProject, text, ''));

          jQuery('.modal-overlay').remove();
          addDatePicker();
          if(!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId);
          }
        });
        if(_.isUndefined(currentProject) || currentProject.id === 0){
          projectCollection.setDirtyRoadParts(projectCollection.getReservedDirtyRoadParts());
          projectCollection.createProject(data);
        } else {
          projectCollection.saveProject(data);
        }
      };

      var nextStage = function(){
        var data = $('#roadAddressProject').get(0);
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          currentProject = result.project;
          currentProject.isDirty = false;
          jQuery('.modal-overlay').remove();
          if(!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId);
          }
          eventbus.trigger('roadAddressProject:openProject', result.project);
          rootElement.html(selectedProjectLinkTemplate(currentProject, options, selectedProjectLink));
          _.defer(function(){
            applicationModel.selectLayer('roadAddressProject');
            $('[id^=editProject]').css('visibility', 'visible');
            $('#closeProject').css('visibility', 'visible');
          });
        });
        if(_.isUndefined(currentProject) || currentProject.id === 0){
          projectCollection.setDirtyRoadParts(projectCollection.getReservedDirtyRoadParts());
          projectCollection.createProject(data);
        } else {
          projectCollection.saveProject(data);
        }
      };

      var fillForm = function (currParts, newParts) {
        if (newParts.length === 0) hasNewRoadParts = false;
        rootElement.html(openProjectTemplate(currentProject, writeHtmlList(currParts), writeHtmlList(newParts)));
        applicationModel.setProjectButton(true);
        applicationModel.setProjectFeature(currentProject.id);
        applicationModel.setOpenProject(true);
        activeLayer = true;
        rootElement.find('.btn-reserve').prop("disabled", false);
        rootElement.find('.btn-save').prop("disabled", false);
        rootElement.find('.btn-next').prop("disabled", false);
      };

      eventbus.on('roadAddress:newProject', function() {
        currentProject=undefined; //clears old data
        $("#roadAddressProject").html("");
        rootElement.html(newProjectTemplate());
        jQuery('.modal-overlay').remove();
        addDatePicker();
        applicationModel.setOpenProject(true);
        activeLayer = true;
        projectCollection.clearRoadAddressProjects();
        _.defer(function(){
          $('#generalNext').prop('disabled', true);
        });
      });

      eventbus.on('roadAddress:openProject', function(result) {
        currentProject = result.project;
        currentProject.isDirty = false;
        projectCollection.clearRoadAddressProjects();
        projectCollection.setCurrentRoadPartList(result.projectLinks);
        var text = '';
        var index = 0;
        _.each(result.projectLinks, function(line){
          var button = projectCollection.getDeleteButton(index++, line.roadNumber, line.roadPartNumber);
          text += '<div style="display:inline-block;">'  + button +
            addSmallLabel(line.roadNumber)+
            addSmallLabel(line.roadPartNumber)+ addSmallLabel(line.roadLength)+ addSmallLabel(line.discontinuity)+ addSmallLabel(line.ely) +
            '</div>';
        });
        rootElement.html(openProjectTemplate(currentProject, text, ''));
        jQuery('.modal-overlay').remove();
        setTimeout(function(){}, 0);
        if(!_.isUndefined(currentProject))
          eventbus.trigger('linkProperties:selectedProject', result.linkId);
        applicationModel.setProjectButton(true);
        applicationModel.setProjectFeature(currentProject.id);
        applicationModel.setOpenProject(true);
        activeLayer = true;
        rootElement.find('.btn-reserve').prop("disabled", false);
        rootElement.find('.btn-next').prop("disabled", false);
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectValidationFailed', function (result) {
        new ModalConfirm(result.success.toString());
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectValidationSucceed', function () {
        rootElement.find('.btn-next').prop("disabled", formIsInvalid(rootElement));
        rootElement.find('.btn-save').prop("disabled", formIsInvalid(rootElement));
        hasNewRoadParts = true;
      });

      eventbus.on('layer:selected', function(layer) {
        activeLayer = layer === 'linkPropertyLayer';
      });

      eventbus.on('roadAddress:projectFailed', function() {
        applicationModel.removeSpinner();
      });

      rootElement.on('click', '[id^=editProject]', currentProject, function(eventObject){
        applicationModel.addSpinner();
        projectCollection.getProjectsWithLinksById(parseInt(eventObject.currentTarget.value)).then(function(result){
          rootElement.empty();
          setTimeout(function(){}, 0);
          eventbus.trigger('roadAddress:openProject', result);
          if(applicationModel.isReadOnly()) {
            $('.edit-mode-btn:visible').click();
          }
        });
      });

      rootElement.on('click', '#generalNext', function() {
        if(currentProject.isDirty){
          saveChanges();
          _.defer(function(){
            nextStage();
          });
        } else {
          nextStage();
        }
      });

      function textFieldChangeHandler(eventData) {
        if(currentProject) {
          currentProject.isDirty = true;
        }
        if($('#nimi').text() !== "" && $('#alkupvm').text() !== "" && $('#generalNext').is(':disabled')){
          $('#generalNext').prop('disabled', false);
          currentProject.isDirty = true;
        }
      }

      rootElement.on('change','#nimi', textFieldChangeHandler);
      rootElement.on('change','#alkupvm', textFieldChangeHandler);
      rootElement.on('change','#lisatiedot', textFieldChangeHandler);

      rootElement.on('click', '.btn-reserve', function() {
        var data;
        var lists = $('.roadAddressProject');
        if ($('#roadAddressProject').get(0)!==null) {
          data = $('#roadAddressProject').get(0);
        } else {
          data =$('#reservedRoads').get(0);
        }
        projectCollection.checkIfReserved(data);
        return false;
      });

      rootElement.on('click', '.btn-delete', function() {
        var id = this.id;
        var roadNumber = this.attributes.roadNumber.value;
        var roadPartNumber = this.attributes.roadPartNumber.value;
        if (!currentProject) {
          projectCollection.setReservedDirtyRoadParts(projectCollection.deleteRoadPartFromList(projectCollection.getReservedDirtyRoadParts(), roadNumber, roadPartNumber));
          $('#roadpartList').html(writeHtmlList(projectCollection.getReservedDirtyRoadParts()));
        } else
        if(projectCollection.getCurrentRoadPartList()[id] && projectCollection.getCurrentRoadPartList()[id].isDirty){
          new GenericConfirmPopup('Haluatko varmasti poistaa tieosan varauksen ja siihen mahdollisesti tehdyt tieosoitemuutokset?', {
            successCallback: function () {
              removePart(roadNumber, roadPartNumber);
              _.defer(textFieldChangeHandler);
            }
          });
        }
        else {
          removePart(roadNumber, roadPartNumber);
        }
      });

      rootElement.on('change', '.form-group', function() {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });

      rootElement.on('click', '.project-form button.next', function(){
        var data = $('#roadAddressProject').get(0);
        applicationModel.addSpinner();
        eventbus.once('roadAddress:projectSaved', function (result) {
          currentProject = result.project;
          currentProject.isDirty = false;
          jQuery('.modal-overlay').remove();
          if(!_.isUndefined(result.projectAddresses)) {
            eventbus.trigger('linkProperties:selectedProject', result.projectAddresses.linkId);
          }
          eventbus.trigger('roadAddressProject:openProject', result.project);
          rootElement.html(selectedProjectLinkTemplate(currentProject, options, selectedProjectLink));
          _.defer(function(){
            applicationModel.selectLayer('roadAddressProject');
          });
        });
        if(_.isUndefined(currentProject) || currentProject.id === 0){
          projectCollection.setDirtyRoadParts(projectCollection.getReservedDirtyRoadParts());
          projectCollection.createProject(data);
        } else {
          projectCollection.saveProject(data);
        }
      });

      rootElement.on('click', '.project-form button.cancel', function(){
        if (currentProject && hasNewRoadParts) {
          hasNewRoadParts = false;
          projectCollection.setReservedDirtyRoadParts([]);
          fillForm(projectCollection.getCurrentRoadPartList(), projectCollection.getReservedDirtyRoadParts());
        }
        else if(!_.isEqual(projectCollection.getDirtyRoadParts(), projectCollection.getCurrentRoadPartList())){
          new GenericConfirmPopup('Haluatko sulkea projektin ja lalata alkuun? \r\nProjekti tallennetaan keskeneräiseksi.\r\nTallentamattomat muutokset häviävät.', {
            successCallback: function () {
              projectCollection.setCurrentRoadPartList(projectCollection.getCurrentRoadPartList());
              fillForm(projectCollection.getCurrentRoadPartList(), projectCollection.getReservedDirtyRoadParts());
            }
          });
        }
        else if (activeLayer) {
          new GenericConfirmPopup('Haluatko sulkea projektin ja lalata alkuun? \r\nProjekti tallennetaan keskeneräiseksi.\r\nTallentamattomat muutokset häviävät.', {
            successCallback: function () {
              applicationModel.setOpenProject(false);
              rootElement.find('header').toggle();
              rootElement.find('.wrapper').toggle();
              rootElement.find('footer').toggle();
              projectCollection.clearRoadAddressProjects();
              eventbus.trigger('layer:enableButtons', true);
            }
          });
        } else {
          eventbus.trigger('roadAddress:openProject', projectCollection.getCurrentProject());
          eventbus.trigger('roadLinks:refreshView');
        }
      });

      rootElement.on('change', '.input-required', function() {
        rootElement.find('.project-form button.next').attr('disabled', formIsInvalid(rootElement));
        rootElement.find('.project-form button.save').attr('disabled', formIsInvalid(rootElement));
        rootElement.find('#roadAddressProject button.btn-reserve').attr('disabled', projDateEmpty(rootElement));
      });

    };
    bindEvents();
  };
})(this);
