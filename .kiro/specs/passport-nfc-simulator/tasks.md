# Implementation Plan

- [x] 1. Set up project dependencies and basic structure
  - Add SCUBA and JMRTD library dependencies to Android module
  - Add necessary NFC permissions to Android manifest
  - Configure HCE service declaration in manifest
  - _Requirements: 2.4, 4.1, 4.3_

- [x] 2. Create core data models in common module
  - Implement PassportData data class with validation methods
  - Create MRZ generation functionality for BAC/PACE protocols
  - Add date validation and formatting utilities
  - Write unit tests for PassportData validation logic
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 3. Implement NFC event and state management models
  - Create NfcEvent data class and NfcEventType enum
  - Implement SimulationStatus enum and PassportSimulatorUiState
  - Add state validation and transition logic
  - Write unit tests for state management models
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 4. Create repository interface and common business logic
  - Define NfcSimulatorRepository interface in common module
  - Implement validation logic for passport data
  - Create error handling and result wrapper types
  - Write unit tests for business logic validation
  - _Requirements: 1.5, 4.5, 5.4_

- [ ] 5. Implement ViewModel for UI state management
  - Create PassportSimulatorViewModel with StateFlow management
  - Implement passport data update and validation methods
  - Add simulation control methods (start/stop)
  - Handle error states and user feedback
  - Write unit tests for ViewModel state transitions
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 3.1, 3.5_

- [ ] 6. Build Compose UI components for passport input
  - Create PassportInputForm with text fields for passport details
  - Implement date picker components for birth and expiry dates
  - Add real-time validation feedback and error display
  - Create form submission and reset functionality
  - Write Compose UI tests for input validation
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 7. Create simulation control and status UI components
  - Implement SimulationControlPanel with start/stop buttons
  - Add NFC availability and permission status indicators
  - Create permission request handling UI
  - Display simulation status with visual feedback
  - Write UI tests for control panel interactions
  - _Requirements: 3.1, 3.5, 4.1, 4.2, 4.4_

- [ ] 8. Build NFC event logging and display components
  - Create EventLogDisplay component for real-time NFC events
  - Implement scrollable event list with timestamps
  - Add event filtering and clearing functionality
  - Create connection status indicators
  - Write UI tests for event display functionality
  - _Requirements: 3.2, 3.3, 3.4_

- [ ] 9. Implement Android NFC repository implementation
  - Create AndroidNfcSimulatorRepository implementing the interface
  - Add NFC hardware availability checking
  - Implement permission request and status monitoring
  - Create Flow-based event emission system
  - Write unit tests for repository implementation
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.2_

- [ ] 10. Create Android HCE service foundation
  - Implement PassportHceService extending HostApduService
  - Add basic APDU command parsing and routing
  - Create service lifecycle management
  - Implement connection and deactivation handling
  - Write unit tests for service lifecycle and APDU parsing
  - _Requirements: 2.1, 2.4, 4.5_

- [ ] 11. Integrate SCUBA library for smart card operations
  - Initialize SCUBA library in HCE service
  - Implement basic smart card response generation
  - Create APDU command validation and error handling
  - Add logging for smart card operations
  - Write integration tests for SCUBA library usage
  - _Requirements: 2.4_

- [ ] 12. Implement BAC authentication protocol
  - Create BAC key derivation from passport MRZ data
  - Implement BAC challenge-response authentication
  - Add BAC protocol state management
  - Handle BAC authentication success and failure cases
  - Write unit tests for BAC protocol implementation
  - _Requirements: 2.2_

- [ ] 13. Implement PACE authentication protocol
  - Create PACE key agreement and authentication
  - Implement PACE protocol state machine
  - Add PACE challenge generation and verification
  - Handle PACE authentication success and failure cases
  - Write unit tests for PACE protocol implementation
  - _Requirements: 2.3_

- [ ] 14. Add comprehensive error handling and logging
  - Implement error code responses for authentication failures
  - Create detailed logging for debugging NFC interactions
  - Add error recovery mechanisms for protocol failures
  - Implement timeout handling for authentication attempts
  - Write tests for error handling scenarios
  - _Requirements: 2.5, 3.4_

- [ ] 15. Create main application screen and navigation
  - Implement main App composable combining all UI components
  - Add proper state management and data flow
  - Create dependency injection setup for repository
  - Implement proper error boundary handling
  - Write integration tests for complete user flows
  - _Requirements: 5.1, 5.3, 5.4_

- [ ] 16. Add comprehensive testing and validation
  - Create end-to-end tests for complete simulation workflow
  - Add performance tests for NFC response times
  - Implement memory usage validation during simulation
  - Create test scenarios for various passport data formats
  - Write integration tests for BAC and PACE protocols
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.2, 2.3, 2.4, 2.5_