--Cases to Remember
--Change_Type == 1 -> Ennallaan(Unchanged) - Source (OLD) is Mandatory, Target (NEW) must be NULL
--Change_Type == 2 -> Uusi(New) - Target (NEW) is mandatory, Source (OLD) must be NULL
--Change_Type == 3 -> Siirto(Move) - Target (NEW) and Source(OLD) are Mandatory
--Change_Type == 4 -> Numerointi(Numbering\Adding sections to an already existant road number for example) -Target (NEW) and Source(OLD) are Mandatory
--Change_Type == 5 -> Lakkautus (Termination\Closing) - Source (OLD) is Mandatory

alter session set nls_language = 'american' NLS_NUMERIC_CHARACTERS = ', ';
--Change_Type 1
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (13253, 1, 11117, 2, 0, 0, 1895, null, null, null, null, null, 5, 1, 1);
--Should return error when converted into JSON
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (13253, 1, null, 2, 0, 0, 1895, null, null, null, null, null, 5, 1, 1);

--Change_Type 2
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (13253, 2, null, null, null, null, null, 1742, 1, 0,  0, 1000, 1, 1, 1);
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (11118, 2, null, null, null, null, null, 11010, 1, 0,  1, 1000, 5, 1, 1);
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (11118, 2, null, null, null, null, null, 11010, 5, 0,  1, 1000, 5, 1, 1);
--Should return error when converted into JSON
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (13253, 2, null, null, null, null, null, 1742, 1, null,  0, 1000, 1, 1, 1);

--Change_Type 3
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (8914, 3, 11007, 2, 0, 0, 1895, 11007, 1, 0,  3616, 5511, 5, 821, 9);
--Should return error when converted into JSON
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (8914, 3, 11007, 2, 0, 0, null, 11007, 1, 0,  null, 5511, 5, 821, 9);

--Change_Type 4
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (8914, 4, 11007, 2, 0, 0, 1895, 11007, 1, 0,  3616, 5511, 5, 821, 9);
--Should return error when converted into JSON
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (8914, 4, 11007, 2, 0, null, 1895, 11007, 1, 0,  3616, null, 5, 821, null);

--Change_Type 5
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (8914, 5, 11007, 2, 0, 0, 1895, null, null, null,  null, null, 5, 821, 9);
--Should return error when converted into JSON
Insert Into ROAD_ADDRESS_CHANGES(PROJECT_ID, CHANGE_TYPE, OLD_ROAD_NUMBER, OLD_ROAD_PART_NUMBER, OLD_TRACK_CODE, OLD_START_ADDR_M, OLD_END_ADDR_M, NEW_ROAD_NUMBER, NEW_ROAD_PART_NUMBER, NEW_TRACK_CODE, NEW_START_ADDR_M, NEW_END_ADDR_M, NEW_DISCONTINUITY, NEW_ROAD_TYPE, NEW_ELY) values (891465797, 5, 11007, 2, 0, 0, 1895, null, null, null,  185, null, 5, 821, 9);
