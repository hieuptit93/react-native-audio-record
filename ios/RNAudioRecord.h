#import <AVFoundation/AVFoundation.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTLog.h>

#define kNumberBuffers 3

typedef struct {
    __unsafe_unretained id      mSelf;
    AudioStreamBasicDescription mDataFormat;
    AudioQueueRef               mQueue;
    AudioQueueBufferRef         mBuffers[kNumberBuffers];
    AudioFileID                 mAudioFile;
    UInt32                      bufferByteSize;
    SInt64                      mCurrentPacket;
    bool                        mIsRunning;
} AQRecordState;

@interface RNAudioRecord : RCTEventEmitter <RCTBridgeModule>
@property (nonatomic, readonly, copy) NSString *filePath;
@property (nonatomic, readonly, strong) AVAudioEngine *audioEngine;
@property (nonatomic, readonly, strong) AVAudioFile *audioFile;
@property (nonatomic, readonly, strong) AVAudioMixerNode *mixerNode;
@property (nonatomic, readonly, assign) BOOL isRecording;
@end
