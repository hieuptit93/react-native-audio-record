#import "RNAudioRecord.h"
#import <AVFoundation/AVFoundation.h>

@interface RNAudioRecord () <AVAudioRecorderDelegate>
@property (nonatomic, readwrite, copy) NSString *filePath;
@property (nonatomic, strong) AVAudioRecorder *audioRecorder;
@property (nonatomic, strong) NSTimer *meteringTimer;
@property (nonatomic, assign) BOOL isRecording;
@end

@implementation RNAudioRecord {
    BOOL hasListeners;
}

RCT_EXPORT_MODULE();

- (dispatch_queue_t)methodQueue {
    return dispatch_get_main_queue();
}

- (BOOL)setupAudioSession {
    NSError *error = nil;
    AVAudioSession *session = [AVAudioSession sharedInstance];
    
    // Request permission first
    if ([session respondsToSelector:@selector(requestRecordPermission:)]) {
        __block BOOL permissionGranted = NO;
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);
        
        [session requestRecordPermission:^(BOOL granted) {
            permissionGranted = granted;
            dispatch_semaphore_signal(sem);
        }];
        
        dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER);
        
        if (!permissionGranted) {
            RCTLogInfo(@"[RNAudioRecord] Record permission denied");
            return NO;
        }
        RCTLogInfo(@"[RNAudioRecord] Record permission granted");
    }
    
    // Configure session with enhanced audio quality
    if (![session setCategory:AVAudioSessionCategoryPlayAndRecord
                       mode:AVAudioSessionModeDefault
                    options:AVAudioSessionCategoryOptionDefaultToSpeaker |
                            AVAudioSessionCategoryOptionAllowBluetooth |
                            AVAudioSessionCategoryOptionMixWithOthers
                      error:&error]) {
        RCTLogInfo(@"[RNAudioRecord] Failed to set category: %@", error);
        return NO;
    }
    
    // Activate session
    if (![session setActive:YES error:&error]) {
        RCTLogInfo(@"[RNAudioRecord] Failed to activate session: %@", error);
        return NO;
    }
    RCTLogInfo(@"[RNAudioRecord] Successfully activated audio session");
    
    // Log session state
    RCTLogInfo(@"[RNAudioRecord] Session state:");
    RCTLogInfo(@"[RNAudioRecord] - Input available: %d", session.isInputAvailable);
    RCTLogInfo(@"[RNAudioRecord] - Sample rate: %f", session.sampleRate);
    RCTLogInfo(@"[RNAudioRecord] - Category: %@", session.category);
    RCTLogInfo(@"[RNAudioRecord] - Mode: %@", session.mode);
    
    return YES;
}

RCT_EXPORT_METHOD(init:(NSDictionary *)options) {
    RCTLogInfo(@"[RNAudioRecord] Initializing with options: %@", options);
    
    // Setup file path
    NSString *fileName = options[@"wavFile"] == nil ? @"audio.wav" : options[@"wavFile"];
    NSString *docDir = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
    self.filePath = [NSString stringWithFormat:@"%@/%@", docDir, fileName];
    
    // Remove existing file
    if ([[NSFileManager defaultManager] fileExistsAtPath:self.filePath]) {
        [[NSFileManager defaultManager] removeItemAtPath:self.filePath error:nil];
    }
    
    // Setup audio session
    if (![self setupAudioSession]) {
        return;
    }
    
    // Get actual session sample rate
    AVAudioSession *session = [AVAudioSession sharedInstance];
    double actualSampleRate = session.sampleRate;
    
    // Setup audio settings
    NSDictionary *settings = @{
        AVFormatIDKey: @(kAudioFormatLinearPCM),
        AVSampleRateKey: @(actualSampleRate),
        AVNumberOfChannelsKey: @1,
        AVLinearPCMBitDepthKey: @16,
        AVLinearPCMIsFloatKey: @(NO),
        AVLinearPCMIsBigEndianKey: @(NO),
        AVLinearPCMIsNonInterleaved: @(NO)
    };
    
    RCTLogInfo(@"[RNAudioRecord] Audio settings: %@", settings);
    
    // Initialize recorder
    NSError *error = nil;
    NSURL *url = [NSURL fileURLWithPath:self.filePath];
    
    self.audioRecorder = [[AVAudioRecorder alloc] initWithURL:url
                                                    settings:settings
                                                       error:&error];
    
    if (error || !self.audioRecorder) {
        RCTLogInfo(@"[RNAudioRecord] Failed to initialize recorder: %@", error);
        return;
    }
    
    self.audioRecorder.delegate = self;
    self.audioRecorder.meteringEnabled = YES;
    
    BOOL prepared = [self.audioRecorder prepareToRecord];
    RCTLogInfo(@"[RNAudioRecord] Prepare to record result: %d", prepared);
    
    if (!prepared) {
        RCTLogInfo(@"[RNAudioRecord] Failed to prepare recorder");
        return;
    }
    
    RCTLogInfo(@"[RNAudioRecord] Setup complete");
}

RCT_EXPORT_METHOD(start) {
    if (!self.audioRecorder) {
        RCTLogInfo(@"[RNAudioRecord] Recorder not initialized");
        return;
    }
    
    // Ensure proper session configuration before starting
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    
    // Log pre-start state
    RCTLogInfo(@"[RNAudioRecord] Pre-start session state:");
    RCTLogInfo(@"[RNAudioRecord] - Category: %@", session.category);
    RCTLogInfo(@"[RNAudioRecord] - Mode: %@", session.mode);
    RCTLogInfo(@"[RNAudioRecord] - Is other audio playing: %d", session.isOtherAudioPlaying);
    
    // Reset session if needed with enhanced settings
    if (![session.category isEqualToString:AVAudioSessionCategoryPlayAndRecord]) {
        RCTLogInfo(@"[RNAudioRecord] Resetting session category to PlayAndRecord");
        
        if (![session setCategory:AVAudioSessionCategoryPlayAndRecord
                           mode:AVAudioSessionModeDefault
                        options:AVAudioSessionCategoryOptionDefaultToSpeaker |
                                AVAudioSessionCategoryOptionAllowBluetooth |
                                AVAudioSessionCategoryOptionMixWithOthers
                          error:&error]) {
            RCTLogInfo(@"[RNAudioRecord] Failed to reset category: %@", error);
            return;
        }
        
        if (![session setActive:YES error:&error]) {
            RCTLogInfo(@"[RNAudioRecord] Failed to reactivate session: %@", error);
            return;
        }
    }
    
    // Verify final session state
    RCTLogInfo(@"[RNAudioRecord] Final session state before recording:");
    RCTLogInfo(@"[RNAudioRecord] - Category: %@", session.category);
    RCTLogInfo(@"[RNAudioRecord] - Mode: %@", session.mode);
    RCTLogInfo(@"[RNAudioRecord] - Is other audio playing: %d", session.isOtherAudioPlaying);
    
    // Start recording
    BOOL started = [self.audioRecorder record];
    RCTLogInfo(@"[RNAudioRecord] Record start result: %d", started);
    
    if (!started) {
        RCTLogInfo(@"[RNAudioRecord] Failed to start recording");
        return;
    }
    
    self.isRecording = YES;
    
    // Start metering timer
    dispatch_async(dispatch_get_main_queue(), ^{
        self.meteringTimer = [NSTimer scheduledTimerWithTimeInterval:0.1
                                                            target:self
                                                          selector:@selector(updateMeters)
                                                          userInfo:nil
                                                           repeats:YES];
    });
    
    RCTLogInfo(@"[RNAudioRecord] Recording started successfully");
}

- (void)updateMeters {
    if (!self.isRecording || !hasListeners || !self.audioRecorder) return;
    
    [self.audioRecorder updateMeters];
    
    float averagePower = [self.audioRecorder averagePowerForChannel:0];
    float peakPower = [self.audioRecorder peakPowerForChannel:0];
    
    // Convert audio levels to linear scale (0-1)
    float normalizedAverage = powf(10.0f, 0.05f * averagePower);
    float normalizedPeak = powf(10.0f, 0.05f * peakPower);
    
    // Create small data packet
    NSMutableData *data = [NSMutableData dataWithLength:4];
    float values[2] = {normalizedAverage, normalizedPeak};
    [data appendBytes:values length:sizeof(values)];
    
    // Send metering data
    [self sendEventWithName:@"data" 
                      body:[data base64EncodedStringWithOptions:0]];
}

RCT_EXPORT_METHOD(stop:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.meteringTimer invalidate];
        self.meteringTimer = nil;
    });
    
    if (self.audioRecorder && self.audioRecorder.recording) {
        [self.audioRecorder stop];
    }
    
    self.isRecording = NO;
    
    // Deactivate session
    NSError *error = nil;
    [[AVAudioSession sharedInstance] setActive:NO 
                                 withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation 
                                     error:&error];
    
    if ([[NSFileManager defaultManager] fileExistsAtPath:self.filePath]) {
        resolve(self.filePath);
    } else {
        reject(@"no_file", @"Recording file not found", nil);
    }
}

- (void)audioRecorderDidFinishRecording:(AVAudioRecorder *)recorder 
                          successfully:(BOOL)flag {
    RCTLogInfo(@"[RNAudioRecord] Recording finished - Success: %d", flag);
}

- (void)audioRecorderEncodeErrorDidOccur:(AVAudioRecorder *)recorder 
                                  error:(NSError *)error {
    RCTLogInfo(@"[RNAudioRecord] Encoding error: %@", error);
}

- (void)startObserving {
    hasListeners = YES;
}

- (void)stopObserving {
    hasListeners = NO;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"data"];
}

@end
