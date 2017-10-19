(function (root) {
  root.RoadAddressProjectEditForm = function(projectCollection, selectedProjectLinkProperty, projectLinkLayer, projectChangeTable) {
    var LinkStatus = projectCollection.getLinkStatus();

    var currentProject = false;
    var selectedProjectLink = false;
    var backend=new Backend();
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
        '<div class="asset-log-info">' + 'Tarkista tekemäsi muutokset.' + '<br>' + 'Jos muutokset ok, tallenna.' + '</div>' +
        '</div>';
      return field;
    };
    var endDistanceOriginalValue = '--';
    var options =['Valitse'];

    var title = function() {
      return '<span class ="edit-mode-title">Uusi tieosoiteprojekti</span>';
    };

    var titleWithProjectName = function(projectName) {
      return '<span class ="edit-mode-title">'+projectName+'<button id="editProject_'+ currentProject.id +'" ' +
        'class="btn-edit-project" style="visibility:hidden;" value="' + currentProject.id + '"></button></span>' +
        '<span id="closeProjectSpan" class="rightSideSpan" style="visibility:hidden;">Poistu projektista</span>';
    };

    var clearInformationContent = function() {
      $('#information-content').empty();
    };

    var sendRoadAddressChangeButton = function() {

      return '<div class="project-form form-controls">' +
        '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button id ="send-button" class="send btn btn-block btn-send">Tee tieosoitteenmuutosilmoitus</button></div>';
    };

    var showProjectChangeButton = function() {
      return '<div class="project-form form-controls">' +
        '<button class="show-changes btn btn-block btn-show-changes">Avaa projektin yhteenvetotaulukko</button>' +
        '<button disabled id ="send-button" class="send btn btn-block btn-send">Tee tieosoitteenmuutosilmoitus</button></div>';
    };

    var actionButtons = function() {
      var html = '<div class="project-form form-controls" id="actionButtons">' +
        '<button class="update btn btn-save"' + (projectCollection.isDirty() ? '' : 'disabled') + '>Tallenna</button>' +
        '<button class="cancelLink btn btn-cancel">Peruuta</button>' +
        '</div>';
      return html;
    };

    var selectedData = function (selected) {
      var span = '';
      if (selected[0]) {
        var link = selected[0];
        var startM = Math.min.apply(Math, _.map(selected, function(l) { return l.startAddressM; }));
        var endM = Math.max.apply(Math, _.map(selected, function(l) { return l.endAddressM; }));
        span = '<div class="project-edit-selections" style="display:inline-block;padding-left:8px;">' +
          '<div class="project-edit">' +
          ' TIE ' + '<span class="project-edit">' + link.roadNumber + '</span>' +
          ' OSA ' + '<span class="project-edit">' + link.roadPartNumber + '</span>' +
          ' AJR ' + '<span class="project-edit">' + link.trackCode + '</span>' +
          ' M:  ' + '<span class="project-edit">' + startM + ' - ' + endM + '</span>' +
          (selected.length > 1 ? ' (' + selected.length + ' linkkiä)' : '')+
          '</div>' +
          '</div>';
      }
      return span;
    };

    var defineOptionModifiers = function(option, selection) {
      var roadIsUnknownOrOther = projectCollection.roadIsUnknown(selection[0]) || projectCollection.roadIsOther(selection[0]) || selection[0].roadLinkSource === 3;
      var toEdit = selection[0].id === 0;
      var linkStatus = selection[0].status;
      var modifiers = '';

      switch(option) {
        case LinkStatus.Unchanged.action: {
          if(roadIsUnknownOrOther){
            modifiers = 'disabled hidden';
          } else {
            modifiers = '';
          }
          break;
        }
        case LinkStatus.Transfer.action: {
          if(roadIsUnknownOrOther){
            modifiers = 'disabled hidden';
          } else if(toEdit){
            modifiers = 'disabled';
          }
          break;
        }
        case LinkStatus.New.action: {
          var enableStatusNew = (selection[0].status !== LinkStatus.NotHandled.value && selection[0].status !== LinkStatus.Terminated.value)|| selection[0].roadLinkSource === 3;
          if(!roadIsUnknownOrOther) {
            if(!enableStatusNew)
              modifiers = 'disabled';
          }
          break;
        }
        case LinkStatus.Terminated.action: {
          if(roadIsUnknownOrOther){
            modifiers = 'disabled hidden';
          } else {
            var status = _.uniq(_.map(selection, function(l) { return l.status; }));
            if (status.length == 1)
              status = status[0];
            else
              status = 0;
            if (status === LinkStatus.Terminated.value){
              modifiers = 'selected';
            } else if(selection[0].roadLinkSource === 3) {
              modifiers = 'disabled';
            }
          }
          break;
        }
        case LinkStatus.Numbering.action: {
          if(roadIsUnknownOrOther){
            modifiers = 'disabled hidden';
          } else if(toEdit) {
            modifiers = 'disabled';
          } else if(selection[0].status === LinkStatus.Terminated.value) {
            modifiers = 'hidden';
          }
          break;
        }
        case LinkStatus.Revert.action: {
          if(roadIsUnknownOrOther){
            modifiers = 'disabled hidden';
          } else if(toEdit) {
            modifiers = 'disabled';
          }
          break;
        }
        default: {
          modifiers = 'selected disabled hidden';
        }
      }
      return modifiers;
    };

    var selectedProjectLinkTemplate = function(project, optionTags, selected) {
      var selection = selectedData(selected);
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
        '<label>Toimenpiteet,' + selection  + '</label>' +
        '<div class="input-unit-combination">' +
        '<select class="form-control" id="dropDown" size="1">'+
        '<option '+ defineOptionModifiers('', selected) +'>Valitse</option>'+
        '<option value='+ LinkStatus.Unchanged.action+' ' + defineOptionModifiers(LinkStatus.Unchanged.action, selected) + '>Ennallaan</option>'+
        '<option value='+ LinkStatus.Transfer.action + ' ' + defineOptionModifiers(LinkStatus.Transfer.action, selected) + '>Siirto</option>'+
        '<option value='+ LinkStatus.New.action + ' ' + defineOptionModifiers(LinkStatus.New.action, selected) +'>Uusi</option>'+
        '<option value='+ LinkStatus.Terminated.action + ' ' + defineOptionModifiers(LinkStatus.Terminated.action, selected) + '>Lakkautus</option>'+
        '<option value='+ LinkStatus.Numbering.action + ' ' + defineOptionModifiers(LinkStatus.Numbering.action, selected) + '>Numerointi</option>'+
        '<option value='+ LinkStatus.Revert.action + ' ' + defineOptionModifiers(LinkStatus.Revert.action, selected) + '>Palautus aihioksi tai tieosoitteettomaksi</option>' +
        '</select>'+
        '</div>'+
        newRoadAddressInfo() +
        '</form>' +
        changeDirection()+
        actionSelectedField()+
        '</div>'+
        '</div>' +
        '</div>'+
        '</div>'+
        '<footer>' + actionButtons() + '</footer>');
    };

    var newRoadAddressInfo = function(){
      return '<div class="form-group new-road-address" hidden>' +
        '<div><label></label></div><div><label style = "margin-top: 50px">TIEOSOITTEEN TIEDOT</label></div>' +
        addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('AJR')+ addSmallLabel('ELY')  + addSmallLabel('JATKUU')+
        '</div>' +
        '<div class="form-group new-road-address" id="new-address-input1" hidden>'+
        addSmallInputNumber('tie',(selectedProjectLink[0].roadNumber !== 0 ? selectedProjectLink[0].roadNumber : '')) +
        addSmallInputNumber('osa',(selectedProjectLink[0].roadPartNumber !== 0 ? selectedProjectLink[0].roadPartNumber : '')) +
        addSmallInputNumber('ajr',(selectedProjectLink[0].trackCode !== 99 ? selectedProjectLink[0].trackCode : '')) +
        addSmallInputNumberDisabled('ely', selectedProjectLink[0].elyCode) +
        addDiscontinuityDropdown() +
        addSmallLabel('TIETYYPPI') +
        roadTypeDropdown() +
        distanceValue() +
        '</div>';
    };

    function replaceAddressInfo()
    {
      if (selectedProjectLink[0].roadNumber === 0 && selectedProjectLink[0].roadPartNumber === 0 && selectedProjectLink[0].trackCode === 99 )
      {
        backend.getNonOverridenVVHValuesForLink(selectedProjectLink[0].linkId, function (response) {
          if (response.success) {
            $('#tie').val(response.roadNumber);
            $('#osa').val(response.roadPartNumber);
          }
        });
      }
    }

    var roadTypeDropdown = function() {
      return '<select class="form-control" id="roadTypeDropDown" size = "1" style="width: auto !important; display: inline">' +
        '<option value = "1">1 Yleinen tie</option>'+
        '<option value = "2">2 Lauttaväylä yleisellä tiellä</option>'+
        '<option value = "3">3 Kunnan katuosuus</option>'+
        '<option value = "4">4 Yleisen tien työmaa</option>'+
        '<option value = "5">5 Yksityistie</option>'+
        '<option value = "9">9 Omistaja selvittämättä</option>' +
        '<option value = "99">99 Ei määritelty</option>' +
        '</select>';
    };

    var distanceValue = function() {
      return '<div id="distanceValue" hidden>' +
        '<div class="form-group" style="margin-top: 15px">' +
        '<img src="images/calibration-point.svg" style="margin-right: 5px" class="calibration-point"/>' +
        '<label class="control-label-small" style="display: inline">ETÄISYYSLUKEMA VALINNAN</label>' +
        '</div>' +
        '<div class="form-group">' +
        '<label class="control-label-small" style="float: left; margin-top: 10px">ALLUSSA</label>' +
        addSmallInputNumber('beginDistance', '--') +
        '<label class="control-label-small" style="float: left;margin-top: 10px">LOPUSSA</label>' +
        addSmallInputNumber('endDistance', '--') +
        '<span id="manualCPWarning" class="manualCPWarningSpan">!</span>' +
        '</div></div>';
    };

    var addDiscontinuityDropdown = function(){
      if(selectedProjectLink[0].endAddressM === 0){
        return '<select class="form-select-control" id="discontinuityDropdown" size="1">'+
          '<option value = "5" selected disabled hidden>5 Jatkuva</option>'+
          '</select>';
      }
      else {
        return '<select class="form-select-control" id="discontinuityDropdown" size="1">' +
          '<option value = "5" selected disabled hidden>5 Jatkuva</option>' +
          '<option value="1" >1 Tien loppu</option>' +
          '<option value="2" >2 Epäjatkuva</option>' +
          '<option value="3" >3 ELY:n raja</option>' +
          '<option value="4" >4 Lievä epäjatkuvuus</option>' +
          '<option value="5" >5 Jatkuva</option>' +
          '</select>';
      }
    };

    var changeDirection = function () {
      return '<div hidden class="form-group changeDirectionDiv" style="margin-top:15px">' +
        '<button class="form-group changeDirection btn btn-primary">Käännä kasvusuunta</button>' +
        '</div>';
    };

    var addSmallLabel = function(label){
      return '<label class="control-label-small">'+label+'</label>';
    };

    var addSmallInputNumber = function(id, value){
      //Validate only number characters on "onkeypress" including TAB and backspace
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
        '"class="form-control small-input roadAddressProject" id="'+id+'" value="'+(_.isUndefined(value)? '' : value )+'" onclick=""/>';
    };

    var addSmallInputNumberDisabled = function(id, value){
      return '<input type="text" class="form-control small-input roadAddressProject" id="'+id+'" value="'+(_.isUndefined(value)? '' : value )+'" readonly="readonly"/>';
    };

    var emptyTemplate = function(project) {
      var selection = selectedData(selectedProjectLink);

      return _.template('' +
        '<header style ="display:-webkit-inline-box;">' +
        titleWithProjectName(project.name) +
        '</header>' +
        '<footer>'+showProjectChangeButton()+'</footer>');
    };

    var isProjectPublishable = function(){
      return projectCollection.getPublishableStatus();
    };

    var checkInputs = function () {
      var rootElement = $('#feature-attributes');
      var inputs = rootElement.find('input');
      var filled = true;
      for (var i = 0; i < inputs.length; i++) {
        if (inputs[i].type === 'text' && !inputs[i].value) {
          filled = false;
        }
      }
      if (filled) {
        rootElement.find('.project-form button.update').prop("disabled", false);
      } else {
        rootElement.find('.project-form button.update').prop("disabled", true);
      }
    };

    var toggleAditionalControls = function(){
      $('[id^=editProject]').css('visibility', 'visible');
      $('#closeProjectSpan').css('visibility', 'visible');
    };

    var changeDropDownValue = function (statusCode) {
      var rootElement = $('#feature-attributes');
      if (statusCode === LinkStatus.Unchanged.value) {
        $("#dropDown").val(LinkStatus.Unchanged.action).change();
      }
      else if(statusCode === LinkStatus.New.value){
        $("#dropDown").val(LinkStatus.New.action).change();
        projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
        rootElement.find('.new-road-address').prop("hidden", false);
        if(selectedProjectLink[0].id !== 0)
          rootElement.find('.changeDirectionDiv').prop("hidden", false);
      }
      else if (statusCode == LinkStatus.Transfer.value) {
        $("select option[value*="+LinkStatus.New.action+"]").prop('disabled',true);
        $('#dropDown').val(LinkStatus.Transfer.action).change();
      }
      $('#discontinuityDropdown').val(selectedProjectLink[selectedProjectLink.length - 1].discontinuity);
      $('#roadTypeDropDown').val(selectedProjectLink[0].roadTypeId);
    };

    var fillDistanceValues = function (selectedLinks) {
      if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === 3) {
        $('#beginDistance').val(selectedLinks[0].startAddressM);
        $('#endDistance').val(selectedLinks[0].endAddressM);
      } else {
        var orderedByStartM = _.sortBy(selectedLinks, function (l) {
          return l.startAddressM;
        });
        if (orderedByStartM[0].calibrationCode === 2) {
          $('#beginDistance').val(orderedByStartM[0].startAddressM);
        }
        if (orderedByStartM[orderedByStartM.length - 1].calibrationCode === 1) {
          $('#endDistance').val(orderedByStartM[orderedByStartM.length - 1].endAddressM);
          endDistanceOriginalValue = orderedByStartM[orderedByStartM.length - 1].endAddressM;
        }
      }
    };

    var bindEvents = function() {

      var rootElement = $('#feature-attributes');
      var toggleMode = function(readOnly) {
        rootElement.find('.wrapper read-only').toggle();
      };

      eventbus.on('projectLink:clicked', function(selected) {
        selectedProjectLink = selected;
        currentProject = projectCollection.getCurrentProject();
        clearInformationContent();
        rootElement.html(selectedProjectLinkTemplate(currentProject.project, options, selectedProjectLink));
        replaceAddressInfo();
        checkInputs();
        toggleAditionalControls();
        changeDropDownValue(selectedProjectLink[0].status);
      });

      eventbus.on('roadAddress:projectFailed', function() {
        applicationModel.removeSpinner();
      });

      eventbus.on('roadAddress:projectLinksUpdateFailed',function(errorCode){
        applicationModel.removeSpinner();
        if (errorCode == 400){
          return new ModalConfirm("Päivitys epäonnistui puutteelisten tietojen takia. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode == 401){
          return new ModalConfirm("Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.");
        } else if (errorCode == 412){
          return new ModalConfirm("Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode == 500){
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.");
        } else {
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.");
        }
      });

      eventbus.on('roadAddress:projectLinksUpdated',function(data){
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        if (typeof data !== 'undefined' && typeof data.publishable !== 'undefined' && data.publishable) {
          eventbus.trigger('roadAddressProject:projectLinkSaved', data.id, data.publishable);
        }
        else {
          eventbus.trigger('roadAddressProject:projectLinkSaved', data.id, data.publishable);
        }
      });

      eventbus.on('roadAddress:projectSentSuccess', function() {
        new ModalConfirm("Muutosilmoitus lähetetty Tierekisteriin.");
        //TODO: make more generic layer change/refresh
        applicationModel.selectLayer('linkProperty');

        rootElement.empty();
        clearInformationContent();

        selectedProjectLinkProperty.close();
        projectCollection.clearRoadAddressProjects();
        projectCollection.reset();
        applicationModel.setOpenProject(false);

        eventbus.trigger('roadAddressProject:deselectFeaturesSelected');
        eventbus.trigger('roadLinks:refreshView');
      });

      eventbus.on('roadAddress:projectSentFailed', function(error) {
        new ModalConfirm(error);
      });

      eventbus.on('roadAddress:projectLinksCreateSuccess', function () {
        eventbus.trigger('projectChangeTable:refresh');
        projectCollection.setTmpDirty([]);
        rootElement.find('.changeDirectionDiv').prop("hidden", false);
      });

      eventbus.on('roadAddress:changeDirectionFailed', function(error) {
        new ModalConfirm(error);
      });

      rootElement.on('click','.changeDirection', function () {
        projectCollection.changeNewProjectLinkDirection(projectCollection.getCurrentProject().project.id, selectedProjectLinkProperty.get());
      });

      eventbus.on('roadAddress:projectLinksSaveFailed', function (result) {
        new ModalConfirm(result.toString());
      });

      eventbus.on('roadAddressProject:discardChanges',function(){
        cancelChanges();
      });

      var saveChanges = function(){
        currentProject = projectCollection.getCurrentProject();
        //TODO revert dirtyness if others than ACTION_TERMINATE is choosen, because now after Lakkautus, the link(s) stay always in black color
        if( $('[id=dropDown] :selected').val() == LinkStatus.Unchanged.action) {
          projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Unchanged.value);
          rootElement.html(emptyTemplate(currentProject.project));
        }
        else if( $('[id=dropDown] :selected').val() === LinkStatus.New.action){
          projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.New.value);
          rootElement.html(emptyTemplate(currentProject.project));
        }
        else if( $('[id=dropDown] :selected').val() === LinkStatus.Transfer.action){
          projectCollection.saveProjectLinks(selectedProjectLink, LinkStatus.Transfer.value);
        }
        else if( $('[id=dropDown] :selected').val() === LinkStatus.Numbering.action){
          projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Numbering.value);
          rootElement.html(emptyTemplate(currentProject.project));
        }
        else if( $('[id=dropDown] :selected').val() === LinkStatus.Terminated.action){
          projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Terminated.value);
          rootElement.html(emptyTemplate(currentProject.project));
        }
        else if( $('[id=dropDown] :selected').val() === LinkStatus.Numbering.action){
          projectCollection.saveProjectLinks(projectCollection.getTmpDirty(), LinkStatus.Numbering.value);
          rootElement.html(emptyTemplate(currentProject.project));
        }
        else if( $('[id=dropDown] :selected').val() === LinkStatus.Revert.action){
          projectCollection.revertChangesRoadlink(selectedProjectLink);
          rootElement.html(emptyTemplate(currentProject.project));
        }
      };

      var cancelChanges = function() {
        if(projectCollection.isDirty()) {
          projectCollection.revertLinkStatus();
          projectCollection.setDirty([]);
          projectCollection.setTmpDirty([]);
          projectLinkLayer.clearHighlights();
          $('.wrapper').remove();
          eventbus.trigger('roadAddress:projectLinksEdited');
          eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
          eventbus.trigger('roadAddressProject:reOpenCurrent');
        } else {
          eventbus.trigger('roadAddress:openProject', projectCollection.getCurrentProject());
          eventbus.trigger('roadLinks:refreshView');
        }
      };

      rootElement.on('change', '#endDistance', function(eventData){
        var changedValue = parseInt(eventData.target.value);
        if(!isNaN(changedValue) && !isNaN(parseInt(endDistanceOriginalValue)) && changedValue !== endDistanceOriginalValue)
          $('#manualCPWarning').css('display', 'inline-block');
        else $('#manualCPWarning').css('display', 'none');
      });

      rootElement.on('click', '.project-form button.update', function() {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', true);
        saveChanges();
      });

      rootElement.on('change', '#dropDown', function() {
        eventbus.trigger('roadAddressProject:toggleEditingRoad', false);
        $('#tie').prop('disabled',false);
        $('#osa').prop('disabled',false);
        $('#ajr').prop('disabled',false);
        $('#discontinuityDropdown').prop('disabled',false);
        $('#roadTypeDropDown').prop('disabled',false);
        if(this.value == LinkStatus.Terminated.action) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          projectCollection.setDirty(projectCollection.getDirty().concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Terminated.value, 'roadLinkSource': link.roadLinkSource};
          })));
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
        else if(this.value == LinkStatus.New.action){
          projectCollection.setTmpDirty(_.filter(projectCollection.getTmpDirty(), function (l) { return l.status !== LinkStatus.Terminated.value;}).concat(selectedProjectLink));
          rootElement.find('.new-road-address').prop("hidden", false);
          if(selectedProjectLink[0].id !== 0) {
            fillDistanceValues(selectedProjectLink);
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
            rootElement.find('#distanceValue').prop("hidden", false);
          }
        }
        else if(this.value == LinkStatus.Unchanged.action){
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          $('#tie').prop('disabled',true);
          $('#osa').prop('disabled',true);
          $('#ajr').prop('disabled',true);
          $('#discontinuityDropdown').prop('disabled',false);
          $('#roadTypeDropDown').prop('disabled',false);
          projectCollection.setDirty(projectCollection.getDirty().concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Unchanged.value, 'roadLinkSource': link.roadLinkSource};
          })));
          projectCollection.setTmpDirty(projectCollection.getTmpDirty().concat(selectedProjectLink));
        }
        else if(this.value == LinkStatus.Transfer.action) {
          projectCollection.setDirty(_.filter(projectCollection.getDirty(), function(dirty) {return dirty.status !== LinkStatus.Unchanged.value;}).concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Transfer.value, 'roadLinkSource': link.roadLinkSource};
          })));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          if(selectedProjectLink[0].id !== 0)
            rootElement.find('.changeDirectionDiv').prop("hidden", false);
        }
        else if(this.value == LinkStatus.Numbering.action) {
          new ModalConfirm("Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosalle.");
          $('#ajr').prop('disabled',true);
          $('#discontinuityDropdown').prop('disabled',true);
          $('#roadTypeDropDown').prop('disabled',true);
          projectCollection.setDirty(projectCollection.getDirty().concat(_.map(selectedProjectLink, function (link) {
            return {'linkId': link.linkId, 'status': LinkStatus.Numbering.value, 'roadLinkSource': link.roadLinkSource};
          })));
          projectCollection.setTmpDirty(projectCollection.getDirty());
          rootElement.find('.new-road-address').prop("hidden", false);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
        else if(this.value == LinkStatus.Revert.action) {
          rootElement.find('.new-road-address').prop("hidden", true);
          rootElement.find('.changeDirectionDiv').prop("hidden", true);
          rootElement.find('.project-form button.update').prop("disabled", false);
        }
      });

      rootElement.on('change', '.form-group', function() {
        rootElement.find('.action-selected-field').prop("hidden", false);
      });

      rootElement.on('click', '.project-form button.cancelLink', function(){
        cancelChanges();
      });

      rootElement.on('click', '.project-form button.send', function(){
        projectCollection.publishProject();
      });

      rootElement.on('click', '.project-form button.show-changes', function(){
        $(this).empty();
        projectChangeTable.show();
        var publishButton = sendRoadAddressChangeButton();
        var projectChangesButton = showProjectChangeButton();
        if(isProjectPublishable()) {
          $('#information-content').html('' +
            '<div class="form form-horizontal">' +
            '<p>' + 'Validointi ok. Voit tehdä tieosoitteenmuutosilmoituksen' + '<br>' +
            'tai jatkaa muokkauksia.' + '</p>' +
            '</div>');
          $('footer').html(publishButton);
        }
        else
          $('footer').html(projectChangesButton);
      });

      rootElement.on('keyup','.form-control.small-input', function () {
        checkInputs();
      });

    };
    bindEvents();
  };
})(this);
